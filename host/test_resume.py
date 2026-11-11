#!/usr/bin/env python3
"""End-to-end tests for the adbw resumable link, with no Android device needed.

    test client ("adb server") -> bridge 127.0.0.1:<port> -> chaos proxy -> bridge :agent port
        <- simulated agent (same protocol as the Kotlin agent) -> fake adbd (echo server)

The chaos proxy kills or silently freezes the agent link every few hundred ms while megabytes
flow both ways. The test passes only if every byte arrives intact and in order over ONE inner
connection: the fake adbd must see exactly one connection, as real adbd would.

Run: python3 test_resume.py
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import random
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import adbw as A  # noqa: E402

log = logging.getLogger("test")
KEY = "11" * 32


# ---------------------------------------------------------------------------------------------
class SimAgent:
    """Python twin of the Kotlin agent's link logic, for testing the bridge."""

    def __init__(self, bridge_port: int, target_port: int, device_id="sim-device", key=KEY,
                 verify_bridge=True):
        self.bridge_port, self.target_port, self.device_id = bridge_port, target_port, device_id
        self.key = key
        self.verify_bridge = verify_bridge
        self.rejected = None
        self.session: A.Session | None = None
        self.links = 0
        self.task: asyncio.Task | None = None
        self.link: A.Link | None = None
        self.stopped = False

    def start(self):
        self.task = asyncio.create_task(self.run())

    def stop(self):
        # Close the link explicitly: on Python 3.11, wait_for() can swallow task cancellation.
        self.stopped = True
        if self.link:
            self.link.close()
        if self.task:
            self.task.cancel()
        if self.session:
            self.session.finish()

    async def run(self):
        while not self.stopped:
            try:
                r, w = await asyncio.open_connection("127.0.0.1", self.bridge_port)
            except OSError:
                await asyncio.sleep(0.1)
                continue
            link = A.Link(r, w, "bridge")
            self.link = link
            if self.stopped:
                link.close()
                return
            self.links += 1
            try:
                s = self.session
                na = os.urandom(16).hex()
                link.send(A.jframe(A.T_HELLO, {"v": A.PROTO_VERSION, "device": self.device_id,
                                               "nonce": na}))
                t, p = await asyncio.wait_for(A.read_frame(r), 5)
                if t == A.T_REJECT:
                    self.rejected = json.loads(p)["error"]
                    return
                assert t == A.T_CHALLENGE, t
                ch = json.loads(p)
                if self.verify_bridge and ch["mac"] != A.link_mac(self.key, "bridge", na, ch["nonce"]):
                    self.rejected = "bridge failed authentication"
                    return
                link.send(A.jframe(A.T_AUTH, {"mac": A.link_mac(self.key, "agent", na, ch["nonce"]),
                                              "name": "sim", "session": s.sid if s else None,
                                              "rx": s.rx if s else 0}))
                t, p = await asyncio.wait_for(A.read_frame(r), 5)
                if t == A.T_REJECT:
                    self.rejected = json.loads(p)["error"]
                    return
                assert t == A.T_WELCOME, t
                wel = json.loads(p)
                if s and wel["resume"]:
                    s.attach(link, wel["rx"])
                elif s:
                    s.finish()
                await self.loop(link)
            except (asyncio.IncompleteReadError, ConnectionError, OSError, asyncio.TimeoutError):
                pass
            finally:
                link.close()
                if self.session:
                    self.session.detach()
            await asyncio.sleep(0.05)

    async def loop(self, link: A.Link):
        async def ticker():
            while not link.closed:
                await asyncio.sleep(0.2)
                s = self.session
                if s and s.link is link and s.rx != s.rx_acked:
                    s.send_ack()
                link.send(A.u64frame(A.T_PING, A.now_ms()))
                await link.drain()
        tk = asyncio.create_task(ticker())
        try:
            while True:
                t, p = await asyncio.wait_for(A.read_frame(link.reader), A.LINK_TIMEOUT)
                s = self.session
                if t == A.T_OPEN:
                    sid = json.loads(p)["session"]
                    if s:
                        s.finish()
                    try:
                        ir, iw = await asyncio.open_connection("127.0.0.1", self.target_port)
                    except OSError as e:
                        link.send(A.jframe(A.T_OPEN_FAIL, {"session": sid, "error": str(e)}))
                        continue
                    s = A.Session(sid, ir, iw, label="agent")
                    s.on_finished = self._finished
                    s.opened = True
                    self.session = s
                    s.start()
                    link.send(A.jframe(A.T_OPEN_OK, {"session": sid, "target": "echo"}))
                    s.attach(link, 0)
                elif t == A.T_DATA and s:
                    await s.on_data(A.U64.unpack_from(p)[0], p[8:])
                elif t == A.T_ACK and s:
                    s.on_ack(A.U64.unpack(p)[0])
                elif t == A.T_PING:
                    link.send(A.frame(A.T_PONG, p))
                elif t == A.T_CLOSE and s:
                    msg = json.loads(p)
                    if msg["session"] == s.sid:
                        if msg.get("abort"):
                            s.local_eof = True
                            s.finish()
                        else:
                            s.on_close(msg["final"])
        finally:
            tk.cancel()

    def _finished(self, s):
        if self.session is s:
            self.session = None


# ---------------------------------------------------------------------------------------------
class Chaos:
    """TCP proxy that keeps breaking the agent link: hard kills and silent freezes."""

    def __init__(self, upstream_port: int, min_s=0.2, max_s=0.8, freeze_ratio=0.3):
        self.upstream_port = upstream_port
        self.min_s, self.max_s, self.freeze_ratio = min_s, max_s, freeze_ratio
        self.kills = self.freezes = 0
        self.enabled = True
        self.blocked = False  # refuse all connections (agent "out of range")
        self.rate = 12e6      # bytes/s per direction

    async def start(self) -> int:
        self.server = await asyncio.start_server(self.handle, "127.0.0.1", 0)
        return self.server.sockets[0].getsockname()[1]

    async def handle(self, cr, cw):
        if self.blocked:
            cw.close()
            return
        try:
            ur, uw = await asyncio.open_connection("127.0.0.1", self.upstream_port)
        except OSError:
            cw.close()
            return
        frozen = asyncio.Event()

        async def pipe(r, w):
            try:
                while True:
                    d = await r.read(65536)
                    if not d:
                        break
                    if frozen.is_set():
                        await asyncio.sleep(3600)  # silently swallow: like a dead Wi-Fi link
                    w.write(d)
                    await w.drain()
                    await asyncio.sleep(len(d) / self.rate)  # ~Wi-Fi throughput
            except (ConnectionError, OSError):
                pass
            finally:
                w.close()

        t1 = asyncio.create_task(pipe(cr, uw))
        t2 = asyncio.create_task(pipe(ur, cw))
        if self.enabled:
            await asyncio.sleep(random.uniform(self.min_s, self.max_s))
            if self.enabled:
                if random.random() < self.freeze_ratio:
                    self.freezes += 1
                    frozen.set()  # the link times out on both ends after LINK_TIMEOUT
                    await asyncio.sleep(A.LINK_TIMEOUT + 0.5)
                else:
                    self.kills += 1
                t1.cancel(); t2.cancel()
                cw.close(); uw.close()
        await asyncio.gather(t1, t2, return_exceptions=True)


class EchoAdbd:
    """Stands in for adbd: echoes everything, counts connections."""

    def __init__(self):
        self.connections = 0
        self.eofs = 0
        self.writers = []

    async def start(self) -> int:
        self.server = await asyncio.start_server(self.handle, "127.0.0.1", 0)
        return self.server.sockets[0].getsockname()[1]

    async def handle(self, r, w):
        self.connections += 1
        self.writers.append(w)
        try:
            while True:
                d = await r.read(65536)
                if not d:
                    break
                w.write(d)
                await w.drain()
        except (ConnectionError, OSError):
            pass
        self.eofs += 1
        w.close()


# ---------------------------------------------------------------------------------------------
async def setup(grace=30.0):
    A.LINK_TIMEOUT = 1.0
    A.PING_INTERVAL = 0.3
    state = Path(tempfile.mkdtemp(prefix="adbw-test-"))
    echo = EchoAdbd()
    echo_port = await echo.start()
    A.save_registry(state, {"sim-device": {"name": "sim", "port": random.randint(20000, 40000),
                                           "key": KEY}})
    bridge = A.Bridge("127.0.0.1", 0, state_dir=state, grace=grace, adb=None, beacon=False)
    server = await bridge.start()
    bridge_port = bridge.listen_port
    chaos = Chaos(bridge_port)
    chaos_port = await chaos.start()
    agent = SimAgent(chaos_port, echo_port)
    agent.start()
    for _ in range(100):
        if next(iter(bridge.devices.values())).link:
            break
        await asyncio.sleep(0.05)
    dev = next(iter(bridge.devices.values()))
    return bridge, dev, echo, chaos, agent


async def transfer(dev, total: int, deadline: float):
    r, w = await asyncio.open_connection("127.0.0.1", dev.port)
    sent_hash, recv_hash = hashlib.sha256(), hashlib.sha256()
    received = 0

    async def writer():
        left = total
        while left:
            n = min(left, random.randint(1, 200_000))
            d = os.urandom(n)
            sent_hash.update(d)
            w.write(d)
            await w.drain()
            left -= n

    async def reader():
        nonlocal received
        while received < total:
            d = await r.read(65536)
            if not d:
                raise AssertionError(f"inner stream closed early at {received}/{total}")
            recv_hash.update(d)
            received += len(d)

    await asyncio.wait_for(asyncio.gather(writer(), reader()), deadline)
    return r, w, sent_hash.hexdigest() == recv_hash.hexdigest(), received


async def test_survives_chaos():
    bridge, dev, echo, chaos, agent = await setup()
    total = int(os.environ.get("ADBW_MB", "24")) << 20
    t0 = time.monotonic()
    r, w, ok, received = await transfer(dev, total, 120)
    dt = time.monotonic() - t0
    assert ok, "data corrupted"
    assert echo.connections == 1, f"adbd saw {echo.connections} connections (session not resumed)"
    print(f"  PASS survives chaos: {total >> 20} MiB each way in {dt:.1f}s over "
          f"{agent.links} links ({chaos.kills} kills, {chaos.freezes} silent freezes, "
          f"{dev.stats['resumes']} resumes), 1 adbd connection, sha256 match")

    # Close propagation: "adb server" hangs up -> adbd sees EOF, even mid-chaos.
    w.close()
    for _ in range(200):
        if echo.eofs:
            break
        await asyncio.sleep(0.05)
    assert echo.eofs == 1, "close did not propagate to adbd"
    print("  PASS close from adb side reaches adbd")
    agent.stop()


async def test_device_side_close():
    bridge, dev, echo, chaos, agent = await setup()
    chaos.enabled = False
    r, w, ok, _ = await transfer(dev, 1 << 20, 30)
    assert ok
    echo.writers[0].close()  # adbd drops the connection (e.g. Wireless debugging toggled)
    d = await asyncio.wait_for(r.read(1), 5)
    assert d == b"", "adb side should see EOF"
    print("  PASS close from adbd side reaches adb server")
    agent.stop()


async def test_grace_expiry():
    bridge, dev, echo, chaos, agent = await setup(grace=1.5)
    chaos.enabled = False
    r, w, ok, _ = await transfer(dev, 1 << 20, 30)
    assert ok
    chaos.blocked = True  # device walks out of range for good
    agent.stop()  # agent can't reach us; its link dies
    t0 = time.monotonic()
    d = await asyncio.wait_for(r.read(1), 10)
    assert d == b"", "adb side should see EOF after grace"
    print(f"  PASS session released {time.monotonic() - t0:.1f}s after link loss (grace=1.5s)")


async def test_agent_restart_drops_session():
    bridge, dev, echo, chaos, agent = await setup()
    chaos.enabled = False
    r, w, ok, _ = await transfer(dev, 1 << 20, 30)
    assert ok
    agent.stop()             # agent process dies; it loses the session state...
    agent.session = None
    agent2 = SimAgent(agent.bridge_port, agent.target_port)
    agent2.start()           # ...and a fresh agent connects with no session
    d = await asyncio.wait_for(r.read(1), 10)
    assert d == b"", "stale session must be closed so adb reconnects cleanly"
    # adb would now reconnect; a new session must work.
    r2, w2, ok2, _ = await transfer(dev, 1 << 20, 30)
    assert ok2
    print("  PASS agent restart: stale session closed, new session works")
    agent2.stop()


async def test_auth_rejects():
    bridge, dev, echo, chaos, agent = await setup()
    chaos.enabled = False
    port = agent.bridge_port
    for label, kwargs, expect in (
            ("agent spots a bridge with the wrong key", {"key": "22" * 32}, "bridge failed"),
            ("bridge spots an agent with the wrong key",
             {"key": "22" * 32, "verify_bridge": False}, "wrong key"),
            ("unknown device", {"device_id": "stranger"}, "isn't registered")):
        bad = SimAgent(port, agent.target_port, **kwargs)
        bad.start()
        for _ in range(100):
            if bad.rejected:
                break
            await asyncio.sleep(0.05)
        bad.stop()
        assert bad.rejected and expect in bad.rejected, (label, bad.rejected)
    assert dev.link is not None, "the real agent's link must be unaffected"
    assert bridge.rejects >= 2
    # A device registered while the bridge runs is picked up without a restart.
    reg = A.load_registry(bridge.state_dir)
    reg["late"] = {"name": "late", "port": random.randint(40001, 50000), "key": "33" * 32}
    await asyncio.sleep(0.05)
    A.save_registry(bridge.state_dir, reg)
    os.utime(bridge.state_dir / "devices.json", (time.time() + 2, time.time() + 2))
    late = SimAgent(port, agent.target_port, device_id="late", key="33" * 32)
    late.start()
    for _ in range(100):
        if "late" in bridge.devices and bridge.devices["late"].link:
            break
        await asyncio.sleep(0.05)
    assert bridge.devices["late"].link is not None, late.rejected
    print("  PASS auth: wrong key rejected by both sides, unknown device rejected, "
          "new device accepted live")
    late.stop(); agent.stop()


async def test_two_computers():
    """One device followed by two computers: each bridge has its own id and key for it, each
    gets its own adbd connection, and neither notices what happens to the other."""
    bridge1, dev1, echo, chaos1, agent1 = await setup()
    chaos1.enabled = False
    state2 = Path(tempfile.mkdtemp(prefix="adbw-test-"))
    A.save_registry(state2, {"same-device-here": {"name": "sim", "key": "44" * 32,
                                                  "port": random.randint(20000, 40000)}})
    bridge2 = A.Bridge("127.0.0.1", 0, state_dir=state2, grace=30, adb=None, beacon=False)
    await bridge2.start()
    chaos2 = Chaos(bridge2.listen_port)
    agent2 = SimAgent(await chaos2.start(), agent1.target_port, device_id="same-device-here",
                      key="44" * 32)
    agent2.start()
    dev2 = bridge2.devices["same-device-here"]
    for _ in range(100):
        if dev2.link:
            break
        await asyncio.sleep(0.05)
    assert dev2.link, "second computer never got its link"

    # Both computers push at once; the second one's link is being killed and frozen throughout.
    (r1, w1, ok1, _), (r2, w2, ok2, _) = await asyncio.gather(
        transfer(dev1, 4 << 20, 60), transfer(dev2, 4 << 20, 60))
    assert ok1 and ok2, "data corrupted"
    assert echo.connections == 2, f"adbd saw {echo.connections} connections, expected one per computer"

    # The first computer forgets the device. The second keeps its session and keeps working.
    A.save_registry(bridge1.state_dir, {})
    os.utime(bridge1.state_dir / "devices.json", (time.time() + 2, time.time() + 2))
    assert await asyncio.wait_for(r1.read(1), 10) == b"", "forgotten device's session must end"
    for _ in range(100):
        if agent1.rejected:
            break
        await asyncio.sleep(0.05)
    assert agent1.rejected and "isn't registered" in agent1.rejected, agent1.rejected
    w2.write(b"still here")
    await w2.drain()
    assert await asyncio.wait_for(r2.readexactly(10), 20) == b"still here"
    assert echo.connections == 2, "the second computer's session must not have been reopened"
    print(f"  PASS two computers: independent sessions ({chaos2.kills} kills, {chaos2.freezes} "
          "freezes on one), forgetting on one leaves the other running")
    agent1.stop(); agent2.stop()


class FakeDevice:
    """Stands in for adb + a device with the agent installed, for testing `setup` and `forget`.
    Keeps the agent's list of computers the way ConfigReceiver does."""

    def __init__(self, apk_sha: str, agent="0.3.0"):
        self.apk_sha, self.agent = apk_sha, agent
        self.bridges: dict[str, dict] = {}
        self.installs = 0
        self.wireless = "0"
        self.props = {"ro.product.model": "Test Phone", "ro.build.version.sdk": "34",
                      "ro.build.version.release": "14", "ro.serialno": "HW1",
                      "service.adb.tcp.port": "5555"}

    def run(self, adb, *args, timeout=60):
        cmd = " ".join(args)
        if args[0] == "devices":
            return 0, "List of devices attached\n192.168.1.50:5555\tdevice"
        if args[0] == "install":
            self.installs += 1
            return 0, "Success"
        if args[0] != "shell":
            return 0, ""
        if "am broadcast" in cmd:
            x = dict(zip(args[args.index("--es") + 1::3], args[args.index("--es") + 2::3]))
            flags = {args[i + 1] for i, a in enumerate(args) if a == "--ez"}
            self.bridges.pop(x.get("remove_bridge"), None)
            if "bridge_id" in x:
                if "only" in flags:
                    self.bridges.clear()
                self.bridges[x["bridge_id"]] = x
            return 0, f'Broadcast completed: result={len(self.bridges)}, data="ok"'
        if cmd.startswith("shell getprop "):
            return 0, self.props.get(args[1].split()[1], "")
        if "pm path" in cmd:
            return 0, "package:/data/app/agent/base.apk"
        if "sha256sum" in cmd:
            return 0, self.apk_sha + "  /data/app/agent/base.apk"
        if "adb_wifi_enabled" in cmd:
            return 0, self.wireless
        if "versionName" in cmd:
            return 0, f"    versionName={self.agent}"
        return 0, "ok" if cmd == "shell echo ok" else ""


def run_cli(*argv) -> tuple[int, str]:
    import contextlib
    import io
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = A.main(list(argv))
    return rc, buf.getvalue()


async def test_setup_and_forget():
    """`adbw setup` from two computers adds both to the device; --only and forget remove."""
    tmp = Path(tempfile.mkdtemp(prefix="adbw-test-"))
    apk = tmp / "agent.apk"
    apk.write_bytes(b"not really an apk")
    home1, home2 = tmp / "computer1", tmp / "computer2"
    dev = FakeDevice(hashlib.sha256(apk.read_bytes()).hexdigest())
    saved = A.Adb.run, A.find_adb, os.environ.get("ADBW_APK")
    A.Adb.run = lambda self, *a, **k: dev.run(self, *a, **k)
    A.find_adb = lambda explicit=None: "adb"
    os.environ["ADBW_APK"] = str(apk)
    try:
        rc, out = run_cli("setup", "--state-dir", str(home1), "--name", "phone")
        assert rc == 0 and "Registered 'phone'" in out, out
        assert "now follows" not in out, out
        rc, out = run_cli("setup", "--state-dir", str(home2), "--name", "phone")
        assert rc == 0 and "now follows 2 computers" in out, out
        b1, b2 = A.bridge_id(home1), A.bridge_id(home2)
        assert set(dev.bridges) == {b1, b2}
        one, two = dev.bridges[b1], dev.bridges[b2]
        assert one["device_id"] != two["device_id"] and one["key"] != two["key"]
        assert one["bridge_name"] and one["adbd_port"] == "5555" and dev.installs == 0
        # Running setup again on the first computer keeps its id and key (no re-pairing churn).
        rc, out = run_cli("setup", "--state-dir", str(home1))
        assert rc == 0 and dev.bridges[b1]["key"] == one["key"]
        assert dev.bridges[b1]["device_id"] == one["device_id"]

        # Reached over USB (or another relay) with Wireless debugging on and its port hidden from
        # the shell, as on Android 17: the agent is told to find the port itself.
        saved_run, saved_props = dev.run, dict(dev.props)
        dev.run = lambda adb, *a, **k: ((0, "List of devices attached\nUSB123\tdevice")
                                        if a[0] == "devices" else saved_run(adb, *a, **k))
        dev.props.pop("service.adb.tcp.port")
        dev.wireless = "1"
        rc, out = run_cli("setup", "--state-dir", str(home1))
        assert rc == 0 and dev.bridges[b1]["adbd_port"] == "auto", out
        dev.run, dev.props, dev.wireless = saved_run, saved_props, "0"
        run_cli("setup", "--state-dir", str(home1))

        # A computer with an older adbw must not downgrade the agent.
        dev.agent, dev.apk_sha = "9.9.9", "f" * 64
        rc, out = run_cli("setup", "--state-dir", str(home1))
        assert rc == 0 and "newer agent (9.9.9); keeping it" in out and dev.installs == 0, out
        dev.agent, dev.apk_sha = "0.3.0", hashlib.sha256(apk.read_bytes()).hexdigest()

        # forget, with the device reachable through a running bridge: the agent is told.
        reg = A.load_registry(home2)
        dev_id = next(iter(reg))
        (home2 / "status.json").write_text(json.dumps({
            "updated": time.time(), "port": 7100, "devices": [
                {"id": dev_id, "link": "up", "adb_state": "device", "info": {"agent": "0.3.0"}}]}))
        saved_sleep, A.time.sleep = A.time.sleep, lambda s: None
        try:
            rc, out = run_cli("forget", "--state-dir", str(home2), "phone")
        finally:
            A.time.sleep = saved_sleep
            (home2 / "status.json").unlink()  # that "bridge" is not running any more
        assert rc == 0 and "no longer looks for this computer" in out, out
        assert set(dev.bridges) == {b1} and A.load_registry(home2) == {}

        # forget with the device out of reach says what is left behind.
        rc, out = run_cli("forget", "--state-dir", str(home1), "phone")
        assert rc == 0 and "wasn't reachable" in out and set(dev.bridges) == {b1}, out

        # --only evicts every other computer.
        run_cli("setup", "--state-dir", str(home1), "--name", "phone")
        rc, out = run_cli("setup", "--state-dir", str(home2), "--name", "phone", "--only")
        assert rc == 0 and "only computer" in out and set(dev.bridges) == {b2}, out
    finally:
        A.Adb.run, A.find_adb = saved[0], saved[1]
        if saved[2] is None:
            os.environ.pop("ADBW_APK", None)
        else:
            os.environ["ADBW_APK"] = saved[2]
    print("  PASS setup/forget: second computer is added, ids and keys are per computer, "
          "newer agent kept, forget and --only remove")


async def main():
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(name)s %(message)s")
    random.seed(int(os.environ.get("ADBW_SEED", "1234")))
    for t in (test_survives_chaos, test_device_side_close, test_grace_expiry,
              test_agent_restart_drops_session, test_auth_rejects, test_two_computers,
              test_setup_and_forget):
        print(t.__name__)
        await t()
    print("all tests passed")


if __name__ == "__main__":
    asyncio.run(main())
    sys.stdout.flush()
    os._exit(0)  # skip noisy teardown of half-closed test sockets
