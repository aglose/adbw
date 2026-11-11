#!/usr/bin/env python3
"""adbw: wireless adb that survives Wi-Fi drops.

    adb server --> 127.0.0.1:<per-device port> --(adbw bridge)--> link :7100 <-- agent app
    agent app --loopback--> adbd on the device

The agent app on the device dials out to this bridge over a resumable link: every byte is
sequence-numbered and replayed after a reconnect, so adb never sees short network drops. adb's own
encryption and authentication still run end to end between the adb server and adbd.

Commands:
    adbw setup [-s SERIAL]   install and start the agent on a device adb can reach right now
    adbw run                 run the bridge (keep it running while you work)
    adbw status              show devices, link state and adb serials
    adbw devices             list registered devices
    adbw serial NAME         print a device's stable adb serial, e.g. adb -s $(adbw serial pixel)
    adbw logs NAME           show the agent's own log from a device
    adbw forget NAME         remove a registered device

Python 3.9+, standard library only. Needs `adb` (Android platform-tools).
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import uuid
import zipfile
from pathlib import Path
from typing import Optional

__version__ = "0.2.1"

AGENT_PKG = "dev.adbw.agent"

# ---------------------------------------------------------------------------------------------
# Wire protocol v2 (keep in sync with agent/.../Proto.kt)
#   frame   = u8 type | u32 length (big endian) | payload
#   DATA    = u64 seq (byte offset of payload[0] in the stream) | bytes
#   ACK     = u64 rx  (bytes received and delivered so far)
#   PING/PONG = u64 millis
#   others  = UTF-8 JSON
#
# Handshake (mutual, with the per-device key that `adbw setup` put on both sides):
#   agent  -> HELLO     {v, device, nonce}
#   bridge -> CHALLENGE {nonce, mac = HMAC(key, "adbw2-bridge|na|nb")}
#   agent  -> AUTH      {mac = HMAC(key, "adbw2-agent|na|nb"), name, session, rx, ...}
#   bridge -> WELCOME   {serial, resume, rx}      (or REJECT {error} at any point)
# ---------------------------------------------------------------------------------------------
PROTO_VERSION = 2
T_HELLO, T_WELCOME, T_CHALLENGE, T_AUTH, T_REJECT = 0x01, 0x02, 0x03, 0x04, 0x05
T_OPEN, T_OPEN_OK, T_OPEN_FAIL = 0x10, 0x11, 0x12
T_DATA, T_ACK, T_CLOSE = 0x20, 0x21, 0x22
T_PING, T_PONG = 0x30, 0x31
T_STATUS = 0x40

HDR = struct.Struct(">BI")
U64 = struct.Struct(">Q")
MAX_PAYLOAD = 1 << 20
CHUNK = 64 * 1024
TX_CAP = 8 << 20          # replay buffer cap per direction; the inner reader pauses beyond it
ACK_EVERY = 1 << 20       # send an ACK at least every this many received bytes
PING_INTERVAL = 2.0
LINK_TIMEOUT = 10.0       # no frame for this long => link is dead (session survives)
DEFAULT_GRACE = 60.0      # how long a session survives with no link; adb commands wait meanwhile
OFFLINE_WAIT = 15.0       # how long an adb connection waits for an absent agent
DEFAULT_LINK_PORT = 7100  # 7000 is taken by macOS AirPlay Receiver
DEFAULT_BASE_PORT = 7101

log = logging.getLogger("adbw")


class ProtocolError(Exception):
    pass


class Rejected(Exception):
    pass


def frame(t: int, payload: bytes = b"") -> bytes:
    return HDR.pack(t, len(payload)) + payload


def jframe(t: int, obj: dict) -> bytes:
    return frame(t, json.dumps(obj, separators=(",", ":")).encode())


def u64frame(t: int, v: int) -> bytes:
    return frame(t, U64.pack(v))


def data_frame(seq: int, data: bytes) -> bytes:
    return frame(T_DATA, U64.pack(seq) + data)


async def read_frame(reader: asyncio.StreamReader) -> tuple[int, bytes]:
    t, n = HDR.unpack(await reader.readexactly(HDR.size))
    if n > MAX_PAYLOAD:
        raise ProtocolError(f"frame too large: {n}")
    return t, (await reader.readexactly(n) if n else b"")


def now_ms() -> int:
    return int(time.time() * 1000)


def link_mac(key_hex: str, role: str, nonce_a: str, nonce_b: str) -> str:
    msg = f"adbw2-{role}|{nonce_a}|{nonce_b}".encode()
    return hmac.new(bytes.fromhex(key_hex), msg, hashlib.sha256).hexdigest()


# ---------------------------------------------------------------------------------------------
# State on disk (~/.adbw): the device registry is written only by `adbw setup` / `forget`;
# the bridge reads it and picks up changes while running.
# ---------------------------------------------------------------------------------------------
def default_state_dir() -> Path:
    return Path(os.environ.get("ADBW_HOME") or Path.home() / ".adbw")


def load_registry(state_dir: Path) -> dict:
    path = state_dir / "devices.json"
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def save_registry(state_dir: Path, reg: dict) -> None:
    state_dir.mkdir(parents=True, exist_ok=True)
    tmp = state_dir / "devices.json.tmp"
    tmp.write_text(json.dumps(reg, indent=2))
    try:
        os.chmod(tmp, 0o600)  # holds link keys
    except OSError:
        pass
    tmp.replace(state_dir / "devices.json")


def bridge_id(state_dir: Path) -> str:
    """Stable id for this computer's bridge, so agents ignore other people's beacons."""
    path = state_dir / "bridge.json"
    try:
        return json.loads(path.read_text())["id"]
    except (OSError, ValueError, KeyError):
        state_dir.mkdir(parents=True, exist_ok=True)
        bid = secrets.token_hex(4)
        path.write_text(json.dumps({"id": bid}))
        return bid


# ---------------------------------------------------------------------------------------------
# Link: one outer TCP connection. Sessions outlive links.
# ---------------------------------------------------------------------------------------------
class Link:
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, label: str):
        self.reader, self.writer, self.label = reader, writer, label
        self.closed = False
        self.last_rx = time.monotonic()
        self.rtt_ms: Optional[float] = None
        sock = writer.get_extra_info("socket")
        if sock is not None:
            try:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass

    def send(self, data: bytes) -> None:
        if not self.closed:
            try:
                self.writer.write(data)
            except (ConnectionError, OSError, RuntimeError):
                self.close()

    async def drain(self) -> None:
        if self.closed:
            return
        try:
            await self.writer.drain()
        except (ConnectionError, OSError, RuntimeError):
            self.close()

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            try:
                self.writer.close()
            except Exception:
                pass


# ---------------------------------------------------------------------------------------------
# Session: one inner byte stream (one adb transport connection), resumable across links.
# Shared by the bridge and the simulated agent used in tests.
# ---------------------------------------------------------------------------------------------
class Session:
    def __init__(self, sid: str, inner_r: asyncio.StreamReader, inner_w: asyncio.StreamWriter,
                 label: str = ""):
        self.sid = sid
        self.label = label or sid
        self.inner_r, self.inner_w = inner_r, inner_w
        self.tx = bytearray()      # bytes sent to the peer but not yet acked
        self.tx_base = 0           # stream offset of tx[0]
        self.rx = 0                # bytes received from the peer and delivered to inner
        self.rx_acked = 0          # last rx value we sent in an ACK
        self.link: Optional[Link] = None
        self.opened = False        # peer has its inner end connected
        self.local_eof = False     # our inner end is gone; peer must be told (CLOSE)
        self.closed = False
        self.space = asyncio.Event()
        self.space.set()
        self.pump_task: Optional[asyncio.Task] = None
        self.on_finished = None    # callback(session) once fully closed
        self.bytes_up = self.bytes_down = 0

    @property
    def tx_next(self) -> int:
        return self.tx_base + len(self.tx)

    def start(self) -> None:
        self.pump_task = asyncio.create_task(self._pump_inner())

    # -- link attach/detach ------------------------------------------------------------------
    def attach(self, link: Link, peer_rx: int) -> None:
        """(Re)bind to a link and replay everything the peer hasn't received."""
        if peer_rx < self.tx_base or peer_rx > self.tx_next:
            raise ProtocolError(
                f"cannot resume {self.sid}: peer rx={peer_rx} outside [{self.tx_base},{self.tx_next}]")
        self.on_ack(peer_rx)
        self.link = link
        for off in range(0, len(self.tx), CHUNK):
            link.send(data_frame(self.tx_base + off, bytes(self.tx[off:off + CHUNK])))
        if self.rx != self.rx_acked:
            self.send_ack()
        if self.local_eof:
            self._send_close("eof")

    def detach(self) -> None:
        self.link = None

    # -- peer -> us ---------------------------------------------------------------------------
    def on_ack(self, n: int) -> None:
        if n > self.tx_base:
            drop = min(n - self.tx_base, len(self.tx))
            del self.tx[:drop]
            self.tx_base += drop
            if len(self.tx) < TX_CAP:
                self.space.set()

    async def on_data(self, seq: int, data: bytes) -> None:
        if seq > self.rx:
            raise ProtocolError(f"gap in {self.sid}: seq={seq} rx={self.rx}")
        skip = self.rx - seq
        if skip >= len(data):
            return  # duplicate from a replay
        data = data[skip:]
        if self.closed:
            return
        try:
            self.inner_w.write(data)
            await self.inner_w.drain()
        except (ConnectionError, OSError, RuntimeError):
            self._inner_gone()
            return
        self.rx += len(data)
        self.bytes_down += len(data)
        if self.rx - self.rx_acked >= ACK_EVERY:
            self.send_ack()

    def on_close(self, final: int) -> bool:
        """Peer's inner end closed. Returns True if we have everything and are now finished."""
        if self.rx < final:
            return False  # data still in flight; the peer will replay and re-send CLOSE
        if not self.local_eof:
            self.local_eof = True
            self._send_close("peer-closed")
        self.finish()
        return True

    def send_ack(self) -> None:
        if self.link:
            self.link.send(u64frame(T_ACK, self.rx))
            self.rx_acked = self.rx

    # -- inner -> peer ------------------------------------------------------------------------
    async def _pump_inner(self) -> None:
        try:
            while True:
                await self.space.wait()
                data = await self.inner_r.read(CHUNK)
                if not data:
                    break
                seq = self.tx_next
                self.tx += data
                self.bytes_up += len(data)
                if len(self.tx) >= TX_CAP:
                    self.space.clear()
                if self.link and self.opened:
                    self.link.send(data_frame(seq, data))
                    await self.link.drain()
        except (ConnectionError, OSError, asyncio.IncompleteReadError):
            pass
        except asyncio.CancelledError:
            return
        self._inner_gone()

    def _inner_gone(self) -> None:
        if not self.local_eof and not self.closed:
            self.local_eof = True
            log.info("[%s] inner stream closed locally", self.label)
            self._send_close("eof")

    def _send_close(self, reason: str) -> None:
        if self.link and self.opened:
            self.link.send(jframe(T_CLOSE, {"session": self.sid, "final": self.tx_next,
                                            "reason": reason}))

    def abort(self, reason: str) -> None:
        """Tear down now (grace expired, replaced, or peer lost the session)."""
        if self.closed:
            return
        log.info("[%s] session %s aborted: %s", self.label, self.sid, reason)
        if self.link and self.opened:
            self.link.send(jframe(T_CLOSE, {"session": self.sid, "final": self.tx_next,
                                            "reason": reason, "abort": True}))
        self.finish()

    def finish(self) -> None:
        if self.closed:
            return
        self.closed = True
        if self.pump_task and self.pump_task is not asyncio.current_task():
            self.pump_task.cancel()
        try:
            self.inner_w.close()
        except Exception:
            pass
        if self.on_finished:
            self.on_finished(self)


# ---------------------------------------------------------------------------------------------
# Bridge
# ---------------------------------------------------------------------------------------------
class Device:
    def __init__(self, dev_id: str, name: str, port: int, key: str):
        self.id, self.name, self.port, self.key = dev_id, name, port, key
        self.link: Optional[Link] = None
        self.link_event = asyncio.Event()
        self.session: Optional[Session] = None
        self.server: Optional[asyncio.base_events.Server] = None
        self.grace_task: Optional[asyncio.Task] = None
        self.agent_status: dict = {}
        self.agent_info: dict = {}
        self.stats = {"link_ups": 0, "link_downs": 0, "resumes": 0, "sessions": 0}
        self.link_since: Optional[float] = None
        self.adb_state = "?"

    @property
    def serial(self) -> str:
        return f"127.0.0.1:{self.port}"


class Bridge:
    def __init__(self, listen_host="0.0.0.0", listen_port=DEFAULT_LINK_PORT,
                 state_dir: Optional[Path] = None, grace=DEFAULT_GRACE,
                 adb: Optional[str] = None, beacon=True):
        self.listen_host, self.listen_port = listen_host, listen_port
        self.state_dir = state_dir or default_state_dir()
        self.grace = grace
        self.adb = adb
        self.beacon = beacon
        self.devices: dict[str, Device] = {}
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self.id = bridge_id(self.state_dir)
        self._registry_mtime = -1.0
        self.rejects = 0

    # -- registry ----------------------------------------------------------------------------
    async def sync_devices(self) -> None:
        """Load devices.json if it changed; open/close per-device adb ports to match."""
        path = self.state_dir / "devices.json"
        try:
            mtime = path.stat().st_mtime
        except OSError:
            mtime = 0.0
        if mtime == self._registry_mtime:
            return
        self._registry_mtime = mtime
        reg = load_registry(self.state_dir)
        for dev_id, d in reg.items():
            dev = self.devices.get(dev_id)
            if dev is None:
                dev = Device(dev_id, d.get("name", dev_id), int(d["port"]), d.get("key", ""))
                self.devices[dev_id] = dev
                log.info("device %-20s -> adb serial %s", dev.name, dev.serial)
            else:
                dev.name, dev.key = d.get("name", dev.name), d.get("key", dev.key)
            if dev.server is None:
                try:
                    dev.server = await asyncio.start_server(
                        lambda r, w, d=dev: self._handle_adb(d, r, w), "127.0.0.1", dev.port)
                except OSError as e:
                    log.warning("cannot listen on %s for %s: %s", dev.serial, dev.name, e)
        for dev_id in [i for i in self.devices if i not in reg]:
            dev = self.devices.pop(dev_id)
            log.info("device %s removed", dev.name)
            if dev.session:
                dev.session.abort("device forgotten")
            if dev.link:
                dev.link.close()
            if dev.server:
                dev.server.close()

    def _write_status(self) -> None:
        out = []
        for d in self.devices.values():
            s = d.session
            out.append({
                "id": d.id, "name": d.name, "serial": d.serial, "adb_state": d.adb_state,
                "link": "up" if d.link else "down",
                "link_peer": d.link.label if d.link else None,
                "link_up_for_s": round(time.monotonic() - d.link_since, 1) if d.link_since else None,
                "rtt_ms": d.link.rtt_ms if d.link else None,
                "session": None if not s else {
                    "id": s.sid, "opened": s.opened, "unacked": len(s.tx),
                    "bytes_to_device": s.bytes_up, "bytes_from_device": s.bytes_down},
                **d.stats, "agent": d.agent_status, "info": d.agent_info,
            })
        tmp = self.state_dir / "status.json.tmp"
        tmp.write_text(json.dumps({"updated": time.time(), "version": __version__,
                                   "bridge": self.id, "port": self.listen_port,
                                   "rejects": self.rejects, "devices": out}, indent=2))
        tmp.replace(self.state_dir / "status.json")

    # -- agent side ---------------------------------------------------------------------------
    async def _authenticate(self, link: Link, peer: str) -> tuple[Device, dict]:
        def reject(msg: str):
            self.rejects += 1
            link.send(jframe(T_REJECT, {"error": msg}))
            raise Rejected(msg)

        t, p = await asyncio.wait_for(read_frame(link.reader), 10)
        if t != T_HELLO:
            raise ProtocolError("expected HELLO")
        hello = json.loads(p)
        if hello.get("v") != PROTO_VERSION:
            reject(f"protocol v{hello.get('v')} != v{PROTO_VERSION}: rerun `adbw setup` to update the agent")
        await self.sync_devices()
        dev = self.devices.get(str(hello.get("device")))
        if dev is None or not dev.key:
            reject("this device isn't registered with this computer: run `adbw setup`")
        na, nb = str(hello.get("nonce", "")), secrets.token_hex(16)
        if len(na) < 16:
            reject("bad nonce")
        link.send(jframe(T_CHALLENGE, {"nonce": nb, "mac": link_mac(dev.key, "bridge", na, nb)}))
        t, p = await asyncio.wait_for(read_frame(link.reader), 10)
        if t != T_AUTH:
            raise ProtocolError("expected AUTH")
        auth = json.loads(p)
        if not hmac.compare_digest(str(auth.get("mac", "")), link_mac(dev.key, "agent", na, nb)):
            reject("wrong key: rerun `adbw setup` for this device")
        return dev, auth

    async def _handle_agent(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        peer = "%s:%s" % writer.get_extra_info("peername")[:2]
        link = Link(reader, writer, peer)
        dev: Optional[Device] = None
        attached = False
        try:
            dev, auth = await self._authenticate(link, peer)
            dev.agent_info = {k: auth.get(k) for k in ("name", "model", "sdk", "agent")}
            if dev.link:
                log.info("[%s] new link from %s replaces %s", dev.name, peer, dev.link.label)
                dev.link.close()
            sid, peer_rx = auth.get("session"), int(auth.get("rx", 0))
            s = dev.session
            resume = bool(s and s.opened and not s.closed and s.sid == sid)
            if s and not resume and s.opened:
                s.abort("agent does not have this session")
                s = None
            link.send(jframe(T_WELCOME, {
                "v": PROTO_VERSION, "serial": dev.serial, "resume": resume,
                "session": sid if resume else None, "rx": s.rx if resume else 0}))
            dev.link = link
            attached = True
            dev.link_since = time.monotonic()
            dev.stats["link_ups"] += 1
            if dev.grace_task:
                dev.grace_task.cancel()
                dev.grace_task = None
            if resume:
                s.attach(link, peer_rx)
                dev.stats["resumes"] += 1
                log.info("[%s] link up from %s, resumed session %s (replayed %d bytes)",
                         dev.name, peer, s.sid, len(s.tx))
            else:
                log.info("[%s] link up from %s", dev.name, peer)
                if s and not s.opened:
                    link.send(jframe(T_OPEN, {"session": s.sid}))
            dev.link_event.set()
            await self._link_loop(dev, link)
        except Rejected as e:
            log.warning("rejected agent from %s: %s", peer, e)
            await link.drain()
        except (asyncio.IncompleteReadError, ConnectionError, OSError, asyncio.TimeoutError,
                ValueError):
            pass
        except ProtocolError as e:
            log.warning("protocol error from %s: %s", peer, e)
            if attached and dev.session:
                dev.session.abort(str(e))
        finally:
            link.close()
            if attached and dev.link is link:
                dev.link = None
                dev.link_since = None
                dev.link_event.clear()
                dev.stats["link_downs"] += 1
                s = dev.session
                if s:
                    s.detach()
                    if s.opened:
                        log.info("[%s] link down; holding session %s for %ss",
                                 dev.name, s.sid, int(self.grace))
                        dev.grace_task = asyncio.create_task(self._grace(dev, s))
                    else:
                        s.abort("link lost before open")
                else:
                    log.info("[%s] link down", dev.name)

    async def _link_loop(self, dev: Device, link: Link) -> None:
        ticker = asyncio.create_task(self._ticker(dev, link))
        try:
            while True:
                t, p = await asyncio.wait_for(read_frame(link.reader), LINK_TIMEOUT)
                link.last_rx = time.monotonic()
                s = dev.session
                if t == T_DATA:
                    if s and s.opened:
                        await s.on_data(U64.unpack_from(p)[0], p[8:])
                elif t == T_ACK:
                    if s:
                        s.on_ack(U64.unpack(p)[0])
                elif t == T_PING:
                    link.send(frame(T_PONG, p))
                elif t == T_PONG:
                    link.rtt_ms = now_ms() - U64.unpack(p)[0]
                elif t == T_OPEN_OK:
                    msg = json.loads(p)
                    if s and s.sid == msg.get("session") and not s.opened:
                        s.opened = True
                        s.attach(link, 0)
                        log.info("[%s] session %s open -> adbd %s", dev.name, s.sid,
                                 msg.get("target", "?"))
                elif t == T_OPEN_FAIL:
                    msg = json.loads(p)
                    if s and s.sid == msg.get("session"):
                        log.warning("[%s] agent could not reach adbd: %s", dev.name,
                                    msg.get("error"))
                        s.abort("open failed")
                elif t == T_CLOSE:
                    msg = json.loads(p)
                    if s and s.sid == msg.get("session"):
                        if msg.get("abort"):
                            s.local_eof = True
                            s.finish()
                        elif s.on_close(int(msg.get("final", 0))):
                            log.info("[%s] session %s closed by device", dev.name, s.sid)
                elif t == T_STATUS:
                    st = json.loads(p)
                    changed = {k: v for k, v in st.items()
                               if dev.agent_status.get(k) != v and k != "rssi_dbm"}
                    if changed:
                        log.info("[%s] agent: %s", dev.name,
                                 " ".join(f"{k}={v}" for k, v in changed.items()))
                    dev.agent_status = st
        finally:
            ticker.cancel()

    async def _ticker(self, dev: Device, link: Link) -> None:
        last_ping = 0.0
        while not link.closed:
            await asyncio.sleep(0.2)
            s = dev.session
            if s and s.link is link and s.rx != s.rx_acked:
                s.send_ack()
            if time.monotonic() - last_ping >= PING_INTERVAL:
                link.send(u64frame(T_PING, now_ms()))
                last_ping = time.monotonic()
            await link.drain()

    async def _grace(self, dev: Device, s: Session) -> None:
        try:
            await asyncio.sleep(self.grace)
        except asyncio.CancelledError:
            return
        if dev.session is s and dev.link is None:
            s.abort(f"no link for {int(self.grace)}s")

    # -- adb side -----------------------------------------------------------------------------
    async def _handle_adb(self, dev: Device, r: asyncio.StreamReader, w: asyncio.StreamWriter):
        if dev.session and not dev.session.closed:
            dev.session.abort("replaced by new adb connection")
        if dev.link is None:
            try:
                await asyncio.wait_for(dev.link_event.wait(), OFFLINE_WAIT)
            except asyncio.TimeoutError:
                log.debug("[%s] adb connected but agent is offline; refusing", dev.name)
                w.close()
                return
        sid = secrets.token_hex(8)
        s = Session(sid, r, w, label=dev.name)
        s.on_finished = lambda sess, d=dev: self._session_done(d, sess)
        dev.session = s
        dev.stats["sessions"] += 1
        s.start()
        dev.link.send(jframe(T_OPEN, {"session": sid}))
        log.info("[%s] adb server connected; opening session %s", dev.name, sid)

    def _session_done(self, dev: Device, s: Session) -> None:
        if dev.session is s:
            dev.session = None

    # -- background jobs ----------------------------------------------------------------------
    async def _adb_watch(self) -> None:
        """Keep the adb server connected to every device whose agent is online."""
        while True:
            await asyncio.sleep(2)
            if not self.adb:
                continue
            try:
                p = await asyncio.create_subprocess_exec(
                    self.adb, "devices", stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL)
                out, _ = await asyncio.wait_for(p.communicate(), 10)
            except (OSError, asyncio.TimeoutError):
                continue
            states = dict(line.split("\t", 1) for line in out.decode().splitlines()[1:]
                          if "\t" in line)
            for d in list(self.devices.values()):
                new = states.get(d.serial, "absent").strip()
                if new != d.adb_state:
                    log.info("[%s] adb sees %s as %s", d.name, d.serial, new)
                    d.adb_state = new
                if d.link and d.serial not in states and d.session is None:
                    try:
                        p = await asyncio.create_subprocess_exec(
                            self.adb, "connect", d.serial, stdout=asyncio.subprocess.PIPE,
                            stderr=asyncio.subprocess.STDOUT)
                        out, _ = await asyncio.wait_for(p.communicate(), 20)
                        log.info("[%s] adb connect %s: %s", d.name, d.serial, out.decode().strip())
                    except (OSError, asyncio.TimeoutError):
                        pass

    async def _beacon(self) -> None:
        """UDP broadcast so agents on the LAN find this bridge without configuration."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setblocking(False)
        msg = f"ADBW2 {self.listen_port} {self.id} {socket.gethostname()}".encode()
        while True:
            try:
                sock.sendto(msg, ("255.255.255.255", self.listen_port))
            except OSError:
                pass
            await asyncio.sleep(2)

    async def _housekeeping(self) -> None:
        while True:
            try:
                await self.sync_devices()
                self._write_status()
            except OSError:
                pass
            await asyncio.sleep(1)

    async def start(self) -> asyncio.base_events.Server:
        server = await asyncio.start_server(self._handle_agent, self.listen_host, self.listen_port)
        if self.listen_port == 0:
            self.listen_port = server.sockets[0].getsockname()[1]
        await self.sync_devices()
        self._tasks = [asyncio.create_task(self._housekeeping())]
        if self.adb:
            self._tasks.append(asyncio.create_task(self._adb_watch()))
        if self.beacon:
            self._tasks.append(asyncio.create_task(self._beacon()))
        return server

    async def serve_forever(self) -> None:
        server = await self.start()
        addrs = ", ".join("%s:%s" % s.getsockname()[:2] for s in server.sockets)
        log.info("adbw %s bridge %s listening for agents on %s (adb: %s)",
                 __version__, self.id, addrs, self.adb or "not found; no auto-connect")
        if not self.devices:
            log.info("no devices registered yet: connect one with adb and run `adbw setup`")
        async with server:
            await server.serve_forever()


# ---------------------------------------------------------------------------------------------
# adb helpers and device setup
# ---------------------------------------------------------------------------------------------
def find_adb(explicit: Optional[str] = None) -> Optional[str]:
    cands = [explicit, os.environ.get("ADBW_ADB"), shutil.which("adb")]
    exe = "adb.exe" if os.name == "nt" else "adb"
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk",
                 Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk"):
        if root:
            cands.append(str(Path(root) / "platform-tools" / exe))
    for c in cands:
        if c and (shutil.which(c) or Path(c).is_file()):
            return shutil.which(c) or c
    return None


class Adb:
    def __init__(self, exe: str, serial: Optional[str] = None):
        self.exe, self.serial = exe, serial

    def run(self, *args: str, timeout: float = 60) -> tuple[int, str]:
        cmd = [self.exe] + (["-s", self.serial] if self.serial else []) + list(args)
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            return p.returncode, (p.stdout + p.stderr).strip()
        except subprocess.TimeoutExpired:
            return 124, "timed out"

    def shell(self, command: str, timeout: float = 30) -> str:
        rc, out = self.run("shell", command, timeout=timeout)
        return out if rc == 0 else ""

    def prop(self, name: str) -> str:
        return self.shell(f"getprop {name}").strip()


def find_apk(state_dir: Path) -> Optional[Path]:
    """The agent APK: $ADBW_APK, bundled inside the single-file build, next to this file, or a
    local Gradle build in a source checkout."""
    env = os.environ.get("ADBW_APK")
    if env:
        return Path(env) if Path(env).is_file() else None
    here = Path(__file__).resolve().parent
    if here.is_file() and zipfile.is_zipfile(here):  # running from adbw.pyz
        with zipfile.ZipFile(here) as z:
            if "agent.apk" not in z.namelist():
                return None
            data = z.read("agent.apk")
        out = state_dir / "cache" / f"adbw-agent-{__version__}.apk"
        if not out.exists() or out.stat().st_size != len(data):
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(data)
        return out
    for c in (here / "agent.apk", here / "adbw-agent.apk",
              here.parent / "agent/app/build/outputs/apk/release/app-release.apk",
              here.parent / "agent/app/build/outputs/apk/debug/app-debug.apk"):
        if c.is_file():
            return c
    return None


def local_ip_towards(target: str) -> Optional[str]:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect((target, 9))  # no packet is sent; this just picks the route
            return s.getsockname()[0]
    except OSError:
        return None


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "device"


def find_device(reg: dict, name: str) -> Optional[str]:
    for dev_id, d in reg.items():
        if name in (d.get("name"), dev_id, f"127.0.0.1:{d.get('port')}"):
            return dev_id
    matches = [i for i, d in reg.items() if d.get("name", "").startswith(name)]
    return matches[0] if len(matches) == 1 else None


def bridge_running(state_dir: Path) -> Optional[dict]:
    try:
        st = json.loads((state_dir / "status.json").read_text())
        return st if time.time() - st["updated"] < 5 else None
    except (OSError, ValueError, KeyError):
        return None


def cmd_setup(args) -> int:
    state_dir: Path = args.state_dir
    exe = find_adb(args.adb)
    if not exe:
        print("adb not found. Install Android platform-tools, or set ADBW_ADB=/path/to/adb.")
        return 1
    reg = load_registry(state_dir)
    ours = {f"127.0.0.1:{d['port']}" for d in reg.values()}

    # 1. Pick the device.
    rc, out = Adb(exe).run("devices")
    online = [l.split("\t")[0] for l in out.splitlines()[1:] if l.endswith("\tdevice")]
    direct = [s for s in online if s not in ours]
    serial = args.serial
    if not serial:
        if len(direct) == 1:
            serial = direct[0]
        elif not direct and len(online) == 1:
            serial = online[0]  # only reachable through adbw itself: fine for an update
        else:
            print("Connect the device with adb first (USB, `adb pair`/`adb connect`), then rerun."
                  if not direct else "Several devices are connected; pick one with -s SERIAL:")
            for s in direct:
                print("   ", s)
            return 1
    adb = Adb(exe, serial)
    if adb.shell("echo ok").strip() != "ok":
        print(f"adb can't run commands on {serial}. Is it authorized and online?")
        return 1
    model = adb.prop("ro.product.model") or "device"
    sdk = int(adb.prop("ro.build.version.sdk") or 0)
    hw = adb.prop("ro.serialno") or serial
    print(f"Device: {model} (Android {adb.prop('ro.build.version.release')}, API {sdk}) via {serial}")
    if sdk < 30:
        print("The agent needs Android 11 (API 30) or newer.")
        return 1

    # 2. Install or update the agent.
    apk = find_apk(state_dir)
    if not apk:
        print("Agent APK not found. Use the release build of adbw (it bundles the APK), set "
              "ADBW_APK=/path/to/adbw-agent.apk, or build it: (cd agent && ./gradlew assembleRelease)")
        return 1
    via_adbw = serial in ours
    want = hashlib.sha256(apk.read_bytes()).hexdigest()

    def installed_sha() -> str:
        path = adb.shell(f"pm path {AGENT_PKG}").replace("package:", "").strip().splitlines()
        return adb.shell(f"sha256sum {path[0]}").split(" ")[0] if path else ""

    have = installed_sha()
    if have == want:
        print(f"Agent {__version__} is already installed")
    elif via_adbw:
        # The agent is carrying this very connection, and replacing it kills it. So push the APK,
        # install it detached on the device, and wait for the new agent to reconnect.
        print(f"Updating the agent to {__version__} through adbw (the link restarts)")
        tmp = "/data/local/tmp/adbw-agent.apk"
        rc, out = adb.run("push", str(apk), tmp, timeout=180)
        if rc != 0:
            print("  push failed:", out.splitlines()[-1] if out else rc)
            return 1
        adb.run("shell", f"setsid sh -c 'pm install -r -g {tmp}; rm -f {tmp}' </dev/null >/dev/null 2>&1 &",
                timeout=20)
        deadline = time.time() + 90
        time.sleep(5)
        while time.time() < deadline and installed_sha() != want:
            time.sleep(2)
        if installed_sha() != want:
            print("  the device didn't come back with the new agent. Connect it directly with adb "
                  "and rerun `adbw setup`.")
            return 1
    else:
        print(f"Installing agent {__version__}")
        rc, out = adb.run("install", "-r", "-g", str(apk), timeout=180)
        if rc != 0 and "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
            print("  signed with a different key than the installed copy; reinstalling")
            adb.run("uninstall", AGENT_PKG)
            rc, out = adb.run("install", "-g", str(apk), timeout=180)
        if rc != 0:
            print("  install failed:", out.splitlines()[-1] if out else rc)
            return 1

    # 3. One-time permissions (each is optional; say what's lost if it fails).
    grants = [
        ("WRITE_SECURE_SETTINGS", "can't turn Wireless debugging back on by itself"),
        ("ACCESS_FINE_LOCATION", "can't request Wi-Fi scans to rejoin faster"),
        ("ACCESS_BACKGROUND_LOCATION", "can't request Wi-Fi scans with the screen off"),
        ("POST_NOTIFICATIONS", "no status notification"),
    ]
    for perm, lost in grants:
        rc, out = adb.run("shell", f"pm grant {AGENT_PKG} android.permission.{perm}")
        if rc != 0 or "Exception" in out:
            print(f"  note: couldn't grant {perm} ({lost})")
    adb.run("shell", f"dumpsys deviceidle whitelist +{AGENT_PKG}")

    # 4. Work out which adbd port the agent should use.
    tls_port = int(adb.prop("service.adb.tls.port") or 0)
    tcp_port = int(adb.prop("service.adb.tcp.port") or 0)
    m = re.match(r"^(\d+\.\d+\.\d+\.\d+):(\d+)$", serial)
    serial_port = int(m.group(2)) if m and not serial.startswith("127.") else 0
    device_ip = m.group(1) if m and not serial.startswith("127.") else None
    if args.adbd_port:
        adbd_port = str(args.adbd_port)
    elif "_adb-tls-connect" in serial or (serial_port and serial_port == tls_port):
        adbd_port = "auto"
    elif serial_port:
        adbd_port = str(serial_port)          # legacy `adb tcpip` port we are connected through
    elif tls_port:
        adbd_port = "auto"
    elif tcp_port:
        adbd_port = str(tcp_port)
    elif args.tcpip:
        print(f"  opening legacy adb port {args.tcpip} (lasts until the device reboots)")
        adb.run("tcpip", str(args.tcpip))
        time.sleep(3)
        adbd_port = str(args.tcpip)
    elif via_adbw:
        adbd_port = None                      # updating through adbw: keep the agent's setting
    else:
        print("This device has no wireless adb port open. Either turn on Developer options > "
              "Wireless debugging and rerun, or rerun with --tcpip 5555 (lost on reboot).")
        return 1
    mode = {None: "adbd port unchanged", "auto": "Wireless debugging (auto port)"}.get(
        adbd_port, f"fixed adb port {adbd_port}")

    # 5. Register the device here and hand the agent its identity and key.
    dev_id = next((i for i, d in reg.items() if d.get("hw") == hw), None) or str(uuid.uuid4())
    entry = reg.get(dev_id, {})
    name = args.name or entry.get("name") or slug(model)
    taken = {d.get("name") for i, d in reg.items() if i != dev_id}
    base, n = name, 2
    while name in taken:
        name, n = f"{base}-{n}", n + 1
    used = {d["port"] for i, d in reg.items() if i != dev_id}
    port = entry.get("port") or next(p for p in range(args.base_port, args.base_port + 500)
                                     if p not in used)
    key = entry.get("key") or secrets.token_hex(32)
    reg[dev_id] = {"name": name, "port": port, "key": key, "hw": hw, "model": model,
                   "added": entry.get("added") or time.strftime("%Y-%m-%d")}
    save_registry(state_dir, reg)

    running = bridge_running(state_dir)
    link_port = running["port"] if running else args.link_port
    if device_ip is None:
        mm = re.search(r"src (\d+\.\d+\.\d+\.\d+)", adb.shell("ip route get 1.1.1.1"))
        device_ip = mm.group(1) if mm else None
    my_ip = local_ip_towards(device_ip or "192.0.2.1")
    extras = ["--es", "device_id", dev_id, "--es", "key", key,
              "--es", "bridge_id", bridge_id(state_dir),
              "--es", "host_name", f"{socket.gethostname().split('.')[0]}.local:{link_port}"]
    if args.host:
        extras += ["--es", "host", args.host if ":" in args.host else f"{args.host}:{link_port}"]
    elif not via_adbw or args.auto_host:
        extras += ["--es", "host", "auto"]
    if my_ip and not via_adbw:
        extras += ["--es", "host_hint", f"{my_ip}:{link_port}"]
    if adbd_port:
        extras += ["--es", "adbd_port", adbd_port]
    # Config goes to a receiver only the adb shell may call; then the activity starts the service.
    rc, out = adb.run("shell", "am", "broadcast", "--include-stopped-packages",
                      "-n", f"{AGENT_PKG}/.ConfigReceiver", *extras)
    if 'data="ok"' not in out and not via_adbw:  # through adbw the reply can be lost: link restarts
        print("  couldn't configure the agent:", out.splitlines()[-1] if out else rc)
        return 1
    if via_adbw:
        time.sleep(2)
    rc, out = adb.run("shell", "am", "start", "-n", f"{AGENT_PKG}/.MainActivity", "--ez", "start", "true")
    if (rc != 0 or "Error" in out) and not via_adbw:
        print("  couldn't start the agent:", out)
        return 1

    serial_out = f"127.0.0.1:{port}"
    print(f"Registered '{name}' -> adb serial {serial_out}  [{mode}]")
    if not running:
        print("Now start the bridge and keep it running:  adbw run")
        return 0
    print("Waiting for the agent to reach the bridge...", end="", flush=True)
    for _ in range(40):
        time.sleep(0.5)
        st = bridge_running(state_dir) or {}
        d = next((x for x in st.get("devices", []) if x["id"] == dev_id), None)
        if d and d["link"] == "up" and d["adb_state"] == "device":
            print(f" connected.\nUse it with:  adb -s {serial_out} shell")
            return 0
        print(".", end="", flush=True)
    print("\nNot connected yet. Check `adbw status`. If the device can't see this computer's "
          "broadcasts, rerun with --host <this computer's IP or DNS name>.")
    return 2


def cmd_status(args) -> int:
    st = bridge_running(args.state_dir)
    reg = load_registry(args.state_dir)
    if not st:
        print("bridge: not running (start it with `adbw run`)")
        for d in reg.values():
            print(f"  {d['name']:<20} 127.0.0.1:{d['port']}")
        return 1
    print(f"bridge {st.get('bridge')}: running, v{st.get('version')}, agent port {st.get('port')}"
          + (f", {st['rejects']} rejected connections" if st.get("rejects") else ""))
    for d in st["devices"]:
        a, info = d.get("agent") or {}, d.get("info") or {}
        up = d["link"] == "up"
        print(f"\n  {d['name']}  [{d['serial']}]  adb={d['adb_state']}  link={d['link']}"
              + (f" from {d['link_peer']} for {d['link_up_for_s']}s, rtt {d['rtt_ms']}ms" if up else ""))
        print(f"    link ups/downs {d['link_ups']}/{d['link_downs']}, resumes {d['resumes']}, "
              f"sessions {d['sessions']}"
              + (f", agent {info.get('agent')} on {info.get('model')} (API {info.get('sdk')})"
                 if info.get("model") else ""))
        if d["session"]:
            s = d["session"]
            print(f"    session {s['id']}: {s['bytes_to_device']}B to device, "
                  f"{s['bytes_from_device']}B from device, {s['unacked']}B unacked")
        if a:
            print("    agent: " + "  ".join(f"{k}={v}" for k, v in a.items()))
    return 0


def cmd_devices(args) -> int:
    reg = load_registry(args.state_dir)
    if not reg:
        print("no devices registered; connect one with adb and run `adbw setup`")
    for d in reg.values():
        print(f"{d['name']:<20} 127.0.0.1:{d['port']:<6} {d.get('model', ''):<20} added {d.get('added', '?')}")
    return 0


def cmd_serial(args) -> int:
    reg = load_registry(args.state_dir)
    dev_id = find_device(reg, args.name)
    if not dev_id:
        print(f"no device named '{args.name}' (see `adbw devices`)", file=sys.stderr)
        return 1
    print(f"127.0.0.1:{reg[dev_id]['port']}")
    return 0


def cmd_forget(args) -> int:
    reg = load_registry(args.state_dir)
    dev_id = find_device(reg, args.name)
    if not dev_id:
        print(f"no device named '{args.name}' (see `adbw devices`)")
        return 1
    d = reg.pop(dev_id)
    save_registry(args.state_dir, reg)
    exe = find_adb(args.adb)
    if exe:
        Adb(exe).run("disconnect", f"127.0.0.1:{d['port']}")
    print(f"forgot {d['name']}. The agent app is still on the device; stop or uninstall it there.")
    return 0


AGENT_LOG = f"/sdcard/Android/data/{AGENT_PKG}/files/agent.log"


def cmd_logs(args) -> int:
    reg = load_registry(args.state_dir)
    dev_id = find_device(reg, args.name)
    exe = find_adb(args.adb)
    if not dev_id or not exe:
        print(f"no device named '{args.name}'" if not dev_id else "adb not found", file=sys.stderr)
        return 1
    adb = Adb(exe, f"127.0.0.1:{reg[dev_id]['port']}")
    rc, out = adb.run("shell", f"tail -n {int(args.lines)} {AGENT_LOG}")
    print(out if rc == 0 else f"can't read the agent log (is the device connected?): {out}")
    return 0 if rc == 0 else 1


def cmd_run(args) -> int:
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
    if bridge_running(args.state_dir):
        print("a bridge is already running on this computer (see `adbw status`)")
        return 1
    host, _, port = args.listen.rpartition(":")
    adb = None if args.no_auto_connect else find_adb(args.adb)
    bridge = Bridge(host or "0.0.0.0", int(port), args.state_dir, args.grace, adb,
                    not args.no_beacon)
    try:
        asyncio.run(bridge.serve_forever())
    except KeyboardInterrupt:
        pass
    except OSError as e:
        print(f"can't start the bridge: {e}")
        return 1
    finally:
        try:
            (args.state_dir / "status.json").unlink()
        except OSError:
            pass
    return 0


def main(argv: Optional[list] = None) -> int:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--state-dir", type=Path, default=default_state_dir(),
                        help="where adbw keeps its state (default ~/.adbw, or $ADBW_HOME)")
    common.add_argument("--adb", help="path to adb (default: PATH, $ADBW_ADB, then the Android SDK)")
    ap = argparse.ArgumentParser(prog="adbw", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", action="version", version=f"adbw {__version__}")
    sub = ap.add_subparsers(dest="command")

    p = sub.add_parser("run", parents=[common], help="run the bridge")
    p.add_argument("--listen", default=f"0.0.0.0:{DEFAULT_LINK_PORT}",
                   help="address agents connect to (default %(default)s)")
    p.add_argument("--grace", type=float, default=DEFAULT_GRACE,
                   help="seconds a session survives without a link (default %(default)s)")
    p.add_argument("--no-auto-connect", action="store_true", help="don't run `adb connect` for you")
    p.add_argument("--no-beacon", action="store_true", help="don't broadcast UDP discovery beacons")
    p.add_argument("-v", "--verbose", action="store_true")
    p.set_defaults(fn=cmd_run)

    p = sub.add_parser("setup", parents=[common], help="install and start the agent on a device")
    p.add_argument("-s", "--serial", help="adb serial of the device (default: the only one connected)")
    p.add_argument("--name", help="short name for the device (default: its model)")
    p.add_argument("--host", help="pin this computer's IP or DNS name instead of auto-discovery")
    p.add_argument("--auto-host", action="store_true", help="go back to auto-discovery after --host")
    p.add_argument("--adbd-port", type=int, help="force the adbd port the agent relays to")
    p.add_argument("--tcpip", type=int, nargs="?", const=5555,
                   help="open a legacy adb TCP port first (default 5555) if none is open")
    p.add_argument("--link-port", type=int, default=DEFAULT_LINK_PORT, help=argparse.SUPPRESS)
    p.add_argument("--base-port", type=int, default=DEFAULT_BASE_PORT, help=argparse.SUPPRESS)
    p.set_defaults(fn=cmd_setup)

    for name, fn, hlp in (("status", cmd_status, "show bridge and device state"),
                          ("devices", cmd_devices, "list registered devices")):
        p = sub.add_parser(name, parents=[common], help=hlp)
        p.set_defaults(fn=fn)
    for name, fn, hlp in (("serial", cmd_serial, "print a device's adb serial"),
                          ("forget", cmd_forget, "remove a registered device")):
        p = sub.add_parser(name, parents=[common], help=hlp)
        p.add_argument("name")
        p.set_defaults(fn=fn)

    p = sub.add_parser("logs", parents=[common], help="show the agent's log from a device")
    p.add_argument("name")
    p.add_argument("-n", "--lines", default=40)
    p.set_defaults(fn=cmd_logs)

    args = ap.parse_args(argv)
    if not args.command:
        ap.print_help()
        return 0
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
