#!/usr/bin/env python3
"""Flaky-network simulator for real-device testing: point the agent at this instead of the bridge.

    agent -> chaos_proxy :7200 -> bridge :7100

Every few seconds it either hard-kills the agent link (like a roam / Wi-Fi drop) or silently
freezes it (like a dead radio, so both ends must time out). The adb session should survive all
of it.

    python3 chaos_proxy.py --listen 0.0.0.0:7200 --to 127.0.0.1:7100 --every 2-6 --freeze 0.3
"""
import argparse
import asyncio
import random
import time


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="0.0.0.0:7200")
    ap.add_argument("--to", default="127.0.0.1:7100")
    ap.add_argument("--every", default="2-6", help="seconds between faults, min-max")
    ap.add_argument("--freeze", type=float, default=0.3, help="fraction of faults that are silent freezes")
    ap.add_argument("--freeze-for", type=float, default=12.0, help="seconds a frozen link stays frozen")
    args = ap.parse_args()
    lo, hi = map(float, args.every.split("-"))
    lh, lp = args.listen.rsplit(":", 1)
    th, tp = args.to.rsplit(":", 1)
    stats = {"links": 0, "kills": 0, "freezes": 0}

    def say(msg):
        print(time.strftime("%H:%M:%S"), msg, stats, flush=True)

    async def handle(cr, cw):
        try:
            ur, uw = await asyncio.open_connection(th, int(tp))
        except OSError:
            cw.close()
            return
        stats["links"] += 1
        frozen = asyncio.Event()

        async def pipe(r, w):
            try:
                while True:
                    d = await r.read(65536)
                    if not d:
                        break
                    if frozen.is_set():
                        await asyncio.sleep(3600)
                    w.write(d)
                    await w.drain()
            except (ConnectionError, OSError):
                pass
            finally:
                w.close()

        t1, t2 = asyncio.create_task(pipe(cr, uw)), asyncio.create_task(pipe(ur, cw))
        done, _ = await asyncio.wait({t1, t2}, timeout=random.uniform(lo, hi),
                                     return_when=asyncio.FIRST_COMPLETED)
        if not done:
            if random.random() < args.freeze:
                stats["freezes"] += 1
                say("FREEZE link (silently dropping packets)")
                frozen.set()
                await asyncio.sleep(args.freeze_for)
            else:
                stats["kills"] += 1
                say("KILL link")
            t1.cancel(); t2.cancel()
            cw.close(); uw.close()
        await asyncio.gather(t1, t2, return_exceptions=True)

    server = await asyncio.start_server(handle, lh, int(lp))
    say(f"chaos proxy {args.listen} -> {args.to}, fault every {args.every}s")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
