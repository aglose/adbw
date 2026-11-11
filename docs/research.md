# Research: how wireless adb works, why it breaks, and the design behind adbw

*Research notes from before adbw was built. Source citations are to AOSP `main`
(`packages/modules/adb` and `frameworks/base/services/core/java/com/android/server/adb/`).
Hardware testing later corrected some of this: see [field-notes.md](field-notes.md). In
particular, Android 17 no longer switches Wireless debugging off on a roam within one network.*

## TL;DR

Wireless ADB isn't flaky because Wi-Fi is bad. It's flaky because **the adb session is tied
directly to one TCP connection, one random port, one IP, and one Wi-Fi access point (BSSID)**,
and every layer (host adb, adbd, and Android's `AdbDebuggingManager`) tears the session down
the moment any of those changes or pauses for about 10 seconds.

The fix from first principles is to **separate the adb session from the physical link**:

1. A small **device agent** (an app with a foreground service) connects to adbd over
   **loopback** (`127.0.0.1:<tls port>`). Loopback never drops.
2. The agent **dials out** to a **host bridge** over a **resumable transport** (sequence-numbered
   frames with replay after reconnect, or QUIC). This rides out Wi-Fi blips, IP changes, AP
   roaming, and client isolation.
3. The host bridge exposes a **stable `127.0.0.1:<port>`** that the local adb server connects
   to. adb's TLS still runs end to end between the adb server and adbd, so the bridge relays
   bytes it can't read and adds no new trust.
4. The agent keeps the device side healthy: it holds Wi-Fi up (critical on Wear OS), re-enables
   Wireless debugging after a roam, and follows adbd's port when it changes.

Some cheap configuration fixes (Tier 0) also help a lot today, with no code.

---

## 1. How wireless ADB works today

```
 adb CLI ──► adb server (host, :5037) ══TCP/TLS══► adbd (device, random port) 
                 ▲   ▲                                   │
                 │   └─ mDNS browse (_adb-tls-connect)   ├─ mDNS advertise
                 │                                       │
                 └──────── pairing (SPAKE2, _adb-tls-pairing, 6-digit code / QR)
```

**Two modes exist:**

| | Legacy `adb tcpip 5555` | Wireless debugging (Android 11+) |
|---|---|---|
| Transport | Plaintext TCP + RSA auth | TLS 1.3, mutual cert auth |
| Port | Fixed (5555, or whatever you choose) | **Random each time** (`new TlsServer(0)`, `daemon/adb_wifi.cpp:158`) |
| Bootstrap | Needs USB (or an existing connection) each boot | Pairing once, then mDNS auto-connect |
| Survives reboot | No | Yes, if it's re-enabled on a trusted network |
| Gated by Wi-Fi network | No | **Yes, by BSSID** (see §2) |
| mDNS type | `_adb._tcp` | `_adb-tls-connect._tcp`, `_adb-tls-pairing._tcp` |

**Device side.** `AdbService` watches `Settings.Global.ADB_WIFI_ENABLED`. When it flips to 1,
`AdbDebuggingManager` checks that the current Wi-Fi BSSID is in the trusted list. If it is,
adbd starts a TLS server on `INADDR_ANY:0`, registers the `_adb-tls-connect` mDNS service, and
reports the port back to system_server.

**Host side.** The adb server browses mDNS (Bonjour/mDNSResponder by default on macOS;
`ADB_MDNS_OPENSCREEN=1` forces the embedded backend). It auto-connects to
`_adb-tls-connect` services whose keys it paired with (`ADB_MDNS_AUTO_CONNECT`, default
`adb-tls-connect`). `adb connect ip:port` skips discovery entirely.

---

## 2. Why it breaks (root causes, confirmed in source)

### 2.1 Roaming between access points turns Wireless debugging off
`AdbDebuggingManager` listens for `NETWORK_STATE_CHANGED_ACTION`. If the BSSID differs from the
one it started on, it does `Settings.Global.putInt(ADB_WIFI_ENABLED, 0)`, logging
*"Detected wifi network change. Disabling adbwifi."* It also turns off on any Wi-Fi disconnect
or when Wi-Fi is disabled.

The trusted list is **per BSSID** (`isTrustedNetwork(bssid)`), not per SSID. Every mesh node,
and every band on each node (2.4, 5 and 6 GHz each have their own BSSID), is a separate
"network" to Android. On a mesh system, simply walking around the house kills Wireless debugging.
Each new BSSID then needs an "Always allow on this network" tap.

> This is probably the biggest single cause of "it worked a minute ago."

### 2.2 The port is random and changes
adbd binds port 0, so the kernel picks the port. It changes every time Wireless debugging is
toggled (including by 2.1), on every reboot, and whenever adbd restarts. `AdbDebuggingManager`
comments on exactly this: *"In scenarios where adbd is restarted, the tls port may change."*

### 2.3 The host gives up fast
- **Aggressive keepalive:** `socket_spec.cpp` sets `set_tcp_keepalive(fd, 1)`: idle 1 s, interval
  1 s, count 10 (`sysdeps_unix.cpp`). A link that's silent for about 11 s is declared dead. A
  watch in ambient mode, a phone in Doze with Wi-Fi power-save, or a 2–5 s roam hiccup can blow
  through that easily. Current source lets you override it with `ADB_TCP_KEEPALIVE_INTERVAL`.
- **Short reconnect budget:** `ReconnectHandler` retries `kMaxAttempts = 20` times at
  `kDefaultTimeout = 3s` (about 60 s total), then calls `remove_transport()`. The device simply
  vanishes from `adb devices`.
- **Reconnect reuses the same `ip:port`:** For `adb connect ip:port`, the reconnect lambda in
  `connect_device()` re-dials the identical address. If the port moved (2.2) or DHCP changed the
  IP, every attempt fails. Only mDNS-serial transports re-resolve the port.

### 2.4 Discovery depends on multicast, which networks often break
mDNS needs multicast to pass both ways. These all break it:
- AP client isolation (common on guest, office and hotel networks)
- IGMP snooping misconfiguration
- Mesh backhaul dropping multicast
- VPNs or multiple interfaces on the host
- Wi-Fi power-save on the device delaying or dropping multicast, which is worse on watches

AOSP's own docs note the host can't rely on seeing a "create" event for `_adb-tls-connect`.

### 2.5 The device radio goes to sleep (watches especially)
- **Wear OS:** When the watch has a Bluetooth link to the phone, its traffic is proxied over BT
  and **Wi-Fi is turned off to save battery**. Android's Wear docs say an app must call
  `requestNetwork(TRANSPORT_WIFI)` to hold Wi-Fi up. Wireless debugging then dies through 2.1's
  "Wi-Fi disabled" path.
- **Phones and tablets:** Wi-Fi power-save (DTIM) adds hundreds of milliseconds of latency.
  Combined with 2.3's 11 s keepalive, that causes intermittent "offline" flaps.

### 2.6 Authorization expiry
Keys that haven't been used for `adb_allowed_connection_time` (default 7 days) are revoked.
Devices you don't touch for a week need pairing again.

### 2.7 No session continuity anywhere
The adb protocol (and its TLS layer) assumes a reliable byte stream. Any TCP reset loses
in-flight bytes, so every running `shell`, `logcat`, `install` or port-forward dies, and the
transport must redo the CNXN/STLS/AUTH handshake. There is no resume.

---

## 3. First principles: what a stable link requires

| Requirement | What breaks it today | What provides it |
|---|---|---|
| A stable address to dial | Random port, DHCP IP | Device dials **out**; the host listens on a fixed port |
| Discovery without multicast | Client isolation, IGMP | Remembered host endpoints, plus unicast/broadcast fallback, plus an optional rendezvous server |
| The session survives link loss | 1 TCP connection = 1 session | **Resumable framing** (sequence/ack and a replay buffer) or QUIC connection migration |
| Fast but tolerant failure detection | Kernel keepalive, 11 s, binary | App-level heartbeats on the outer link, with **no** timeout on the inner loopback link |
| adbd stays reachable | BSSID gating, Wi-Fi off on Wear | Agent re-enables `adb_wifi_enabled`, holds a Wi-Fi network request, runs as a foreground service |
| No new trust surface | Raw TCP relays | Keep adb's TLS **end to end**; the relay only carries ciphertext |

The key point: **adbd accepts TLS connections from anywhere, including loopback, and authenticates
by key, not by source IP.** Shizuku and LADB already rely on this. They run an adb client on the
device that talks to `127.0.0.1:<tls port>` after finding the port through `NsdManager`. So
nothing on the device has to change: the loopback hop into adbd is always available, and every
reliability problem moves to a link we control.

---

## 4. Proposed design

### Tier 0: config fixes today (no code)
1. **Disable the authorization timeout.** Developer options → "Disable adb authorization
   timeout", or `adb shell settings put global adb_allowed_connection_time 0`.
2. **Loosen the host keepalive:** `export ADB_TCP_KEEPALIVE_INTERVAL=5`, which tolerates about
   55 s of silence. Then run `adb kill-server`. First check that this adb version honours it
   (look at `strings $(which adb) | grep KEEPALIVE`).
3. **Trust every BSSID once.** Walk the house and accept "Always allow on this network" on each
   mesh node and band. Or pin the dev devices to one AP or band.
4. **Prefer mDNS serials** (`adb-XXXX._adb-tls-connect._tcp`) over `ip:port`. Their reconnect
   re-resolves the port.
5. **Watches:** turn off Bluetooth on the watch while debugging, or use the agent (Tier 2) to
   hold Wi-Fi. Enable "Stay awake while charging".
6. **If mDNS is flaky on macOS,** try `ADB_MDNS_OPENSCREEN=1` (or the reverse), and check the
   result with `adb mdns check`.

### Tier 1: host supervisor (`adbw`, host only, about a day of work)
A small daemon (Kotlin/JVM, Go or Python) that:
- Browses `_adb-tls-connect`, `_adb._tcp` and pairing services **itself** (`dns-sd`/zeroconf),
  and keeps a registry: **device GUID → last IP, last port, last seen**.
- Polls `adb devices -l` (or `host:track-devices`). On `offline` or a missing device, it runs
  `adb disconnect` and then `adb connect <fresh ip:port>` with exponential backoff and **no
  attempt cap**.
- When mDNS goes quiet, it unicast-probes the last IP's recent ports and does a cheap TCP SYN
  scan of the ephemeral range (37000–45000 is typical) on the known IP.
- Exposes a stable alias (`adbw shell watch1 …` maps to the current serial).

This addresses 2.2, 2.3 and part of 2.4. It does **not** fix 2.1, 2.5 or 2.7: a roam still
turns Wireless debugging off, and in-flight sessions still die.

### Tier 2: device agent + resumable bridge (the real fix)

```
┌──────────── Device ────────────┐            ┌──────────── Host ─────────────┐
│ adbd TLS :<random>             │            │ adb server                    │
│    ▲ loopback (never drops)    │            │    │ adb connect 127.0.0.1:7001│
│    │                           │            │    ▼                          │
│ Agent (foreground service)     │  outbound  │ adbw bridge  :7001 (per dev)  │
│  • NsdManager → adbd port      │═══════════►│  • listens :7000 for agents   │
│  • resumable link client       │  resumable │  • resumable link server      │
│  • Wi-Fi request / wake mgmt   │   framed   │  • device registry, mDNS beacon│
│  • re-enable adb_wifi_enabled  │  TCP/QUIC  │  • runs `adb connect` for you │
└────────────────────────────────┘            └───────────────────────────────┘
               adb's TLS 1.3 runs end to end through this ⇢ bridge never sees plaintext
```

**Link protocol.** It's deliberately simple, like Eternal Terminal's or mosh's resume:
- Frames: `{stream_id, seq, ack, payload}`. Each side keeps a replay buffer of un-acked bytes
  (cap around 8 MB).
- On reconnect: `HELLO {device_guid, session_id, last_rx_seq}`. The peer replays from that
  point. adb never notices.
- Heartbeat every 2 s. After 6 missed heartbeats, the outer link is marked down and reconnect
  starts at once, but **the inner sockets stay open** for a grace window (say 10 minutes).
  After the window, the bridge closes the host-side socket so adb shows `offline`, and
  reconnects cleanly when the device returns.
- Authenticate the agent with a key pinned at pairing (an mTLS or Noise handshake on the outer
  link). This stops a stranger on the LAN from occupying a device slot. It's
  defence-in-depth: they still couldn't get past adbd's own auth.
- Use QUIC (with connection migration) later if wanted. The hand-rolled TCP version is roughly
  500 lines per side and much easier to debug.

**Agent responsibilities:**

| Problem | Agent action |
|---|---|
| Port changes (2.2) | Watch `_adb-tls-connect` on the device with `NsdManager`. On change, open a new loopback connection and tell the bridge, which gives adb a fresh transport. |
| Roaming disables Wi-Fi debugging (2.1) | Hold `WRITE_SECURE_SETTINGS` (granted once with `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`). On `onAvailable`/BSSID change, write `adb_wifi_enabled=1`. This works on already-trusted BSSIDs. On a new BSSID, show a notification that deep-links to the prompt. |
| Wear Wi-Fi off (2.5) | `requestNetwork(TRANSPORT_WIFI)` while the agent is "armed", and release it when disarmed so the battery is spared. |
| Doze / standby | A foreground service (FGS-state processes keep network access in Doze). Suggest `adb shell dumpsys deviceidle whitelist +<pkg>`. Optionally use `WIFI_MODE_FULL_LOW_LATENCY` while the screen is on. |
| Discovery (2.4) | Dial known host endpoints first, then a UDP broadcast beacon on :7000, then mDNS `_adbw._tcp`, then an optional rendezvous/relay server. This also allows **off-LAN** debugging, e.g. over Tailscale. |
| Reboot | A `BOOT_COMPLETED` receiver starts the FGS and re-enables `adb_wifi_enabled` (on trusted networks). The device is back with no USB. |

**What this buys:**
- Roaming, DHCP renewals and short Wi-Fi drops become **invisible** to the adb session.
  logcat, shell and installs keep going.
- No dependency on multicast or on host-to-device reachability, so client-isolated and NAT'd
  networks work.
- One `adb devices` entry per device with a **stable serial** (`127.0.0.1:7001`). Map serials to
  names in the bridge.

---

## 5. Risks and things to check in a spike

1. **`adb_wifi_enabled` written by a non-system app.** It's a Global setting, so
   `WRITE_SECURE_SETTINGS` should be enough. Tools like Shizuku-starter do this. Verify on the
   target OEMs, including Wear OS images.
2. **Pairing state.** This design doesn't change pairing; host keys stay in
   `adb_known_hosts.pb`. Check that the host's TLS identity is the same whether it dials
   `127.0.0.1:N` or the mDNS name. It should be, since the client cert comes from `~/.android/adbkey`.
3. **Duplicate transports.** mDNS auto-connect may connect directly *as well as* through the
   bridge. Set `ADB_MDNS_AUTO_CONNECT=` (empty) for the adb server the bridge manages.
4. **Long outages.** The replay buffer bounds how much a stalled `install` can buffer. Apply
   back-pressure by having the bridge stop reading from adb when the buffer fills.
5. **Performance.** The extra loopback hop is negligible. Wi-Fi throughput is unchanged. Turning
   off Nagle on both legs keeps shell latency low.
6. **Store/policy.** The agent is a dev tool distributed by sideload or internally, not via Play.
   That's fine for this use.

### Suggested spike, in order
1. Apply Tier 0 and measure the baseline. Log `adb devices` every second for an hour while
   walking between APs, putting the watch in ambient mode, and so on.
2. Proof of concept: a **dumb TCP relay** (no resume) from a device app to `127.0.0.1:<tls port>`,
   then `adb connect host-relay`. This validates that loopback TLS through a relay works, which
   is the core assumption.
3. Add the resume layer and the `adb_wifi_enabled` re-arm. Repeat the hour-long walk test and
   compare flap counts.
4. Add the Wear `requestNetwork`, the boot receiver, and the host registry and CLI.

## Sources
- AOSP adb: `daemon/adb_wifi.cpp`, `socket_spec.cpp`, `sysdeps_unix.cpp`, `transport.cpp`,
  `client/transport_emulator.cpp` (`connect_device`), `client/transport_mdns.cpp`,
  `docs/user/adb.1.md`: https://android.googlesource.com/platform/packages/modules/adb/
- `AdbDebuggingManager.java`, `AdbService.java`:
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/adb/
- ADB Wi-Fi architecture doc: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md
- Wear OS network access (`requestNetwork(TRANSPORT_WIFI)`): https://developer.android.com/training/wearables/data/network-access
- Shizuku-style on-device discovery with `NsdManager` / `_adb-tls-connect`: https://juejin.cn/post/7349763031826808895 ,
  ShizukuPlus connection fallbacks: https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection
