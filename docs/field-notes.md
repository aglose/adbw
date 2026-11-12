# Field notes

What testing on real devices showed. Network names and addresses are removed.

## Fault-injection results

`host/chaos_proxy.py` sits between agent and bridge and, every 2 to 6 seconds, either kills the
connection or silently freezes it for 12 seconds. The same proxy in front of plain adbd gives
the stock-adb baseline.

**Pixel 10 Pro XL, Android 17, Wireless debugging (TLS, paired key)**

| Test | Stock wireless adb | adbw |
|---|---|---|
| 60 s `adb shell` stream | died at second 2 | 60/60 (14 faults, 5 of them freezes; 12 resumes, no new session) |
| 100 MB push | failed ("failed to read copy response") | SHA-256 match, 14.5 MB/s |
| `adb devices` sampled every 0.5 s | `offline` 10 times, about 15 reconnects | `device` 200/200 |
| 143 short commands over 90 s of faults | not run | 143/143; slowest 10.3 s (waited out a freeze) |
| Phone Wi-Fi off 8 s, then on | usable after 87 s | usable after 13 s, same serial |
| 4 real mesh roams in 2 minutes, screen off | survived | survived, one mid-push |

**Android 13 userdebug device, legacy adb port, adbw 0.2 (authenticated link)**

| Test | adbw |
|---|---|
| 50 s `adb shell` stream | 50/50 (12 faults, 3 of them freezes) |
| 50 MB push | SHA-256 match, 9.4 MB/s |
| `adb devices` sampled every 0.5 s | `device` 150/150 |
| Agent updated over adbw itself | back in 13 s |

**Android 14 emulator:** same pattern; stock adb died at the first fault, adbw didn't.

The host tests (`host/test_resume.py`) repeat this without a device: 24 to 48 MiB each way
through kills and freezes, one adbd connection, matching hashes.

## Two computers, one device (0.3)

Pixel 10 Pro XL, Android 17, following two bridges (two state directories on one Mac, standing
in for two computers).

| Test | Result |
|---|---|
| Add computer 2 while computer 1 runs a 40 s `adb shell` stream | 40/40 lines; computer 1's link untouched |
| 30 MB push through computer 2 while computer 1 streams | SHA-256 match (read back through computer 1), 5.4 MB/s; stream 25/25 |
| Kill computer 2's bridge | computer 1 unaffected |
| Start computer 2's bridge again | usable within about a second (found by its beacon) |
| Update the agent through computer 1 | both computers reconnected by themselves |
| `adbw forget` on computer 2 | agent dropped it; computer 1 unaffected |
| `adbw setup --only` on computer 1 | agent dropped computer 2 |

## What real devices taught us

**Android 17 fixed the roaming kill switch.** AOSP `main` at the time of the research turned
Wireless debugging off on every access-point (BSSID) change. The Pixel on Android 17 has an
`AdbWifiNetworkMonitor` that ignores changes within the same network name and turns Wireless
debugging back on when the phone rejoins a trusted network. Older versions still have the
problem; the agent's re-enable covers those.

**Apps can't read adbd's port.** SELinux denies `service.adb.tls.port` to apps. The agent finds
the port through on-device mDNS (`_adb-tls-connect._tcp`, or `_adb._tcp` for legacy ports) in
about a second, including after every port change.

**On Android 17 the adb shell can't read it either.** `getprop service.adb.tls.port` comes back
empty even over adb. `adbw setup` falls back to the `adb_wifi_enabled` setting to learn that
Wireless debugging is on, and leaves finding the port to the agent.

**Polling a denied property is expensive.** An early agent retried `getprop` twice a second.
Each attempt forked a process and logged an SELinux denial, which wasted battery and stalled
the agent's timer with the screen off. It now asks once and remembers the denial.

**Logcat is too small to debug with.** The phone's main log buffer was 256 KiB and rotated in
about a minute. The agent writes its own log; read it with `adbw logs NAME`.

**The settings screen overwrote new settings.** The agent's UI saved its text fields whenever
it paused, clobbering what `adbw setup` had just written. It now writes a field only if the
user edited it.

**Computers change address too.** Between two test days the computer's DHCP address moved, and
another device took the old one. An agent pinned to an IP would have dialed the wrong machine.
The agent now finds the bridge by broadcast and checks the bridge's identity with the device
key.

**Binding a socket to the Wi-Fi network fails on Android 17** (`EPERM`). The agent falls back
to the default network. On a watch that binding is what keeps traffic off Bluetooth, so Wear OS
needs a look.

## The long outages were below adb

Twice the phone dropped off Wi-Fi for 5 to 7 minutes with the screen off. Neither was adb's
doing, and no transport survives minutes without a network.

**Outage 1: slow rescans.** An access point disconnected the phone 24 seconds after a roam to a
weak node. With the screen off, a stationary phone relies on firmware PNO scans: every 60 s,
backing off to 180 s, and only on channels where saved networks were seen before. It rejoined
after 5 min 16 s, at the edge of the signal threshold.

**Outage 2: one bad mesh node.** The phone was in strong range the whole time:

| Step | Result |
|---|---|
| Main network, node A, −48 dBm | DHCP timeout |
| Second saved network, node A, −46 dBm | DHCP timeout |
| Second saved network, node A, −47 dBm | DHCP timeout |
| Android disables both networks for 5 minutes (`DISABLED_DHCP_FAILURE`) | scans "filtered out due to blocklist" |
| Main network, node B (other band), −51 dBm | connected, 7 min 18 s after the drop |

Android blocked the whole network because one node wouldn't hand out an address, and skipped a
healthy node in range. A saved network that reliably fails DHCP is a trap: its strong signal
keeps getting it picked. Fix the node, and forget networks a dev device doesn't need.

**What the agent does about it.** While Wi-Fi is on but disconnected it requests full-band
scans, because Android runs auto-join on every scan result, including app-requested ones. A
foreground service gets 4 scans per 2 minutes, so the worst-case wait drops from 180 s to about
30 s; with Developer options > Wi-Fi scan throttling off it scans every 5 s at first.

Limits:
- Scans need location turned on plus the fine and background location permissions.
  `NEARBY_WIFI_DEVICES` isn't accepted for `startScan()`, and a permission failure comes back
  as a plain `false` that looks like throttling. The agent checks at startup and says what's
  missing.
- An app can't lift a DHCP-failure block or the scan throttle; both need system permissions.
- The booster's benefit for a plain out-of-range drop hasn't been measured yet. Shell-based
  simulations of "the access point vanished" weren't reliable.

## Packaging

**A Homebrew formula without a bottle needs a compiler toolchain, even to copy one file.** The
first end-to-end `brew install` failed on a Mac whose Command Line Tools were older than its
macOS: Homebrew treats any formula without a prebuilt bottle as a source build and refuses to
start. adbw is a single prebuilt file, so it ships as a cask instead, which has no such check.
CI now installs it from the tap on macOS.

## Open items

- Following several computers has only been tried with two bridges on one machine.

- Wear OS: untested, and the Wi-Fi socket binding needs investigating.
- Measure rejoin time with and without the scan booster on a real out-of-range walk.
- One `error: protocol` from an adb command over adbw, during a baseline run. It didn't recur
  in several hundred later commands under faults.
- Throughput is 6 to 27 MB/s; the agent flushes every 64 KB frame and batching would help.
