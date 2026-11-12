# adbw

Wireless adb that survives Wi-Fi drops.

Stock wireless adb ties each session to one TCP connection. A roam, a sleeping radio or a
changed port kills every running `shell`, `logcat` and `install`, and often leaves the device
`offline`. adbw puts a small relay between adb and the device that can lose its connection and
pick up exactly where it left off, so adb never notices.

```
 Your computer                                        Android device (phone, tablet, watch, ...)
 ┌──────────────────────────────────────┐             ┌───────────────────────────────────┐
 │ adb server                           │             │ adbd (Wireless debugging, or a    │
 │   │ adb -s 127.0.0.1:7101 ...        │             │       legacy `adb tcpip` port)    │
 │   ▼                                  │             │   ▲ loopback: never drops         │
 │ adbw run  (the bridge)  ◄════════════╪═ resumable ═╪═  adbw agent (small app)          │
 │  one stable serial per device        │  link, the  │   finds adbd, holds Wi-Fi up,     │
 └──────────────────────────────────────┘  device     └───────────────────────────────────┘
                                           dials out
```

- **One stable serial per device** (`127.0.0.1:7101`), whatever its IP or adbd port is today.
- **Sessions survive drops.** Every byte is numbered and replayed after a reconnect. Under
  injected faults every 2 to 6 seconds, stock adb lost its shell within 2 seconds; adbw kept a
  60-second shell and a 100 MB push intact. See [docs/field-notes.md](docs/field-notes.md).
- **Nothing to configure on the device.** It finds your computer by itself and follows it when
  either IP changes.
- **Several computers at once.** Set the device up from your desktop and your laptop; it keeps
  a link to each one that is around, and each gets its own adb session.
- **adb's own security is untouched.** adb's encryption and key checks still run end to end;
  the relay only carries the bytes. The relay link has its own per-device key on top.

## Install

You need Python 3.9+ and `adb` on the computer, and Android 11+ on the device.

**Single file (macOS, Linux, Windows).** Download `adbw` from the
[latest release](https://github.com/aglose/adbw/releases/latest). The agent APK is inside it.

```bash
chmod +x adbw && sudo mv adbw /usr/local/bin/
```

On Windows, run it as `py adbw ...`.

**Homebrew.**

```bash
brew tap aglose/adbw https://github.com/aglose/adbw
```

Recent Homebrew versions ask you to trust a third-party tap before using it:

```bash
brew trust aglose/adbw
```

```bash
brew install adbw
```

It installs as a cask (one prebuilt file), so it doesn't need Xcode or its Command Line Tools.
It does need `python3` and `adb` on your PATH; `brew install python android-platform-tools`
provides both. If you earlier copied the single file into Homebrew's `bin` folder yourself,
delete that copy first, or the install will refuse to overwrite it.

## Use

**1. Start the bridge** and leave it running. If macOS asks whether Python may accept incoming
connections, allow it.

```bash
adbw run
```

**2. Set up each device once.** Connect it to adb any way that works today (USB, `adb pair` +
`adb connect`, or `adb connect ip:port`), then:

```bash
adbw setup
```

This installs the agent, grants its permissions, gives it a key, and starts it. Use
`-s SERIAL` if several devices are connected and `--name` to pick the name.

**3. Use the stable serial** it prints, from any tool:

```bash
adb -s $(adbw serial pixel) logcat
```

After that the device reconnects by itself whenever it and the bridge are on the same network,
including after a reboot.

## Adding a second computer

A device can follow several computers at once, for example a desktop and a laptop. Setting it
up from another computer adds that computer; it doesn't replace the first. On the new computer,
on the same network as the device:

**1. Install adbw** (see [Install](#install)) and start its bridge:

```bash
adbw run
```

**2. Give that computer adb access to the device once**, in a second terminal. This is plain
adb, exactly as without adbw. Any one of these works:

- USB: plug it in and accept the prompt on the device.
- Wireless debugging: Developer options > Wireless debugging > Pair device with pairing code,
  then `adb pair IP:PORT` with the address and code it shows.
- A legacy `adb tcpip` port that is already open: find the device, then connect to the address
  on its `_adb._tcp` line.

```bash
adb mdns services
```

```bash
adb connect IP:PORT
```

**3. Add the computer:**

```bash
adbw setup --name pixel
```

```
Registered 'pixel' -> adb serial 127.0.0.1:7101  [Wireless debugging (auto port)]
The device now follows 2 computers; the others keep working.
```

How it behaves:

- Each computer has its own name, serial and key for the device. They don't know about each
  other, and both can use the device at the same moment.
- The device connects to whichever of its computers are running `adbw run` on its network. One
  that is away costs a connection attempt every 15 seconds; its return is noticed within seconds.
- Adding a computer never interrupts a session on another one.
- `adbw forget NAME` on a computer removes it from the device's list too.
  `adbw setup --only` makes the computer you run it on the only one, removing all the others.
- A device follows at most 8 computers. A ninth replaces the one it hasn't seen for longest.

## Commands

| Command | What it does |
|---|---|
| `adbw run` | Run the bridge |
| `adbw setup [-s SERIAL] [--name N]` | Install or update the agent on a device and add this computer to it |
| `adbw setup --only` | The same, and remove every other computer from the device |
| `adbw status` | Link state, adb state and counters for every device |
| `adbw devices` | Registered devices and their serials |
| `adbw serial NAME` | Print a device's serial |
| `adbw logs NAME` | The agent's own log from the device |
| `adbw forget NAME` | Remove a device here, and this computer from the device |

## Updating

**1. Update the tool:** `brew upgrade adbw`, or download the new file from the
[latest release](https://github.com/aglose/adbw/releases/latest) over the old one.

**2. Restart the bridge:** press Ctrl-C in the terminal running `adbw run` and start it again.
Devices reconnect by themselves within a few seconds.

**3. Update the agent on each device.** This works over adbw itself; no cable needed. Running
adb commands on that device end while the agent restarts.

```bash
adbw setup -s $(adbw serial pixel)
```

With several computers, update adbw on all of them. One agent serves them all: any computer can
update it, the others keep working with it, and a computer with an older adbw (0.3.0 or
later) leaves a newer agent in place rather than downgrading it. adbw 0.2 can't do that: update
it before running `adbw setup` against a device that already has a 0.3 agent.

## What runs where

| Where | What |
|---|---|
| Computer, TCP 7100 | The bridge listens here; devices connect to it. Change with `adbw run --listen HOST:PORT`. |
| Computer, UDP 7100 | Discovery broadcast, sent every 2 seconds so devices find the bridge. |
| Computer, `127.0.0.1:7101` and up | One port per device: its stable adb serial. Local only. |
| Computer, `~/.adbw` | Registered devices and their keys. Set `ADBW_HOME` to move it. |
| Device | The "adbw agent" app: a foreground service with a status notification. It starts again after a reboot. |

The agent's screen on the device lists the computers it follows and the state of each link, has
Start and Stop buttons, and a few switches (turn Wireless debugging back on after roams, rejoin
Wi-Fi faster, keep the CPU awake, keep the link on Wi-Fi). Computers are added and removed only
with `adbw setup` and `adbw forget`, never on the device.

## Which adbd the agent uses

| Device | What `adbw setup` picks |
|---|---|
| Wireless debugging on (Android 11+) | The Wireless debugging port, found again each time it changes. Survives reboots. |
| Connected through `adb connect ip:port` (legacy `adb tcpip`) | That fixed port. On most devices it resets on reboot. |
| USB only, no wireless port open | Stops and tells you. Turn on Wireless debugging, or rerun with `--tcpip 5555`. |

## Troubleshooting

Start with `adbw status` on the computer. It shows, per device, whether the link is up and what
adb thinks of it. `adbw logs NAME` prints the agent's own log (it needs the link to be up).

**`adbw setup` ends with "Not connected yet".** The device can't reach the computer. Usual
causes: a VPN or firewall on the computer blocking incoming connections on port 7100, or a
network that blocks broadcasts between devices (some office and guest Wi-Fi). Pause the VPN or
allow Python to accept incoming connections, or give the device a fixed address to dial:

```bash
adbw setup --host my-laptop.example.com
```

`--host` takes an IP address or a DNS name. Go back to automatic discovery with
`adbw setup --auto-host`. If the network blocks device-to-device traffic entirely (client
isolation), no wireless adb can work on it, adbw included.

**`adb devices` shows the adbw serial as `offline`.** The bridge is running but the device's
link is down: the device is off, asleep without Wi-Fi, on another network, or its agent isn't
running. It comes back by itself when the device does.

**The agent was stopped** (the Stop button, or a force stop in Android's settings). It won't
start again by itself, on purpose. Open the "adbw agent" app on the device and tap Start, or
connect the device to adb directly and rerun `adbw setup`.

**A legacy `adb tcpip` port is gone after the device rebooted.** That port doesn't survive a
reboot on most devices. Connect over USB and reopen it:

```bash
adbw setup --tcpip 5555
```

Turning on Wireless debugging instead avoids this; its port is found again automatically.

**The agent's log says "bridge refused: this device isn't registered".** That computer no
longer has the device (`adbw forget`, or its `~/.adbw` was deleted). Run `adbw setup` there
again, or remove that computer from the device with `adbw setup --only` from another one.

**"a bridge is already running on this computer".** One bridge serves every device; use the
one that is running.

## What it can't fix

- **adbd itself dropping the session:** Wireless debugging switched off, adbd restarting, a
  reboot. adbw reconnects by itself in a few seconds, but running commands end.
- **Outages longer than a minute.** The bridge holds a session for 60 seconds without a link
  (`adbw run --grace N`), then lets adb see the disconnect.
- **A network that won't let the device back on.** See the DHCP story in the field notes.

## Security

- The agent only relays bytes to adbd on the device's own loopback. adb's authentication
  (paired keys, TLS for Wireless debugging) still decides who gets a shell.
- The link between agent and bridge is mutually authenticated with a per-device key that
  `adbw setup` installs over adb. A stranger on the network can't register as your device, and
  your device won't follow someone else's bridge.
- Adding a computer takes adb access to the device, which already means full control of it.
  Each computer gets a separate key, so removing one (`adbw forget`, or `adbw setup --only`
  from another) doesn't affect the rest.
- The link is authenticated but not separately encrypted. With Wireless debugging, adb's TLS
  already encrypts everything inside it. With a legacy `adb tcpip` port, traffic is as
  unencrypted as it already was without adbw.
- Only the adb shell can change the agent's settings (the receiver requires a permission that
  ordinary apps can't hold).
- Keys live in `~/.adbw/devices.json` on the computer.

## Build from source

```bash
python3 host/test_resume.py
```

```bash
python3 scripts/build_dist.py
```

The first runs the host tests (no device needed). The second builds `dist/adbw` and
`dist/adbw-agent.apk`; it needs JDK 17 and the Android SDK. Set `ADBW_KEYSTORE` and
`ADBW_KEYSTORE_PASSWORD` to sign the agent with a stable key.

To release: bump `__version__` in `host/adbw.py`, tag `vX.Y.Z`, push the tag. The Release
workflow builds, publishes the files and updates the Homebrew cask.

| Path | What |
|---|---|
| `host/adbw.py` | The whole host tool: bridge, setup, status (standard library only) |
| `host/test_resume.py` | Tests: simulated agent, fake adbd, fault-injecting proxy |
| `host/chaos_proxy.py` | Fault injector for real-device testing |
| `agent/` | The Android agent (Kotlin, no dependencies) |
| `docs/research.md` | How wireless adb works and why it breaks |
| `docs/field-notes.md` | Test results and what real devices taught us |

## Releases

| Version | What changed |
|---|---|
| 0.3.1 | Homebrew install fixed: it is now a cask and no longer needs Xcode Command Line Tools. Ctrl-C stops the bridge without a traceback. |
| 0.3.0 | A device can follow several computers at once. `adbw setup --only`; `adbw forget` also tells the device; an older adbw no longer tries to downgrade a newer agent; setup recognises Wireless debugging on Android 17. |
| 0.2.1 | Fixed connecting on Android 17, which refuses sockets bound to the Wi-Fi network. |
| 0.2.0 | First release: single-file tool with the agent inside, per-device keys, automatic discovery, `adbw setup`. |

Files and checksums are on the [releases page](https://github.com/aglose/adbw/releases). The
agent APK in every release is signed with the same key, so updates install over each other.

## Status

Early but working. Tested on a Pixel 10 Pro XL (Android 17, Wireless debugging), an Android 13
userdebug device with a legacy adb port, and an Android 14 emulator, from macOS. Following two
computers was tested on the Pixel with two bridges on one Mac, not yet with two machines. The Homebrew install is checked on macOS in CI. The host tests
also run on Linux and Windows in CI; the device flow hasn't been tried from Windows. Wear OS is
untested.
