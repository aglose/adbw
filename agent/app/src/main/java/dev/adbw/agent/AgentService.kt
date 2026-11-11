package dev.adbw.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps a resumable link from this device to every adbw bridge it follows
 * (one per computer set up with `adbw setup`) and relays each bridge's session into adbd over
 * loopback. The links are independent: each computer gets its own adbd connection.
 *
 * Threads: one link loop per computer (connect / read frames), a beacon listener (UDP), one pump
 * per session (adbd -> bridge), and a 200 ms ticker (ACKs, PINGs, status, Wireless-debugging
 * re-arm).
 */
class AgentService : Service() {
    companion object {
        private const val ACTION_STOP = "dev.adbw.agent.STOP"
        private const val ACTION_RECONNECT = "dev.adbw.agent.RECONNECT"
        private const val CHANNEL = "adbw"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AgentService::class.java))
        }

        /** Start, or if already running, apply changed settings to the links they affect. */
        fun applyConfig(ctx: Context) {
            if (AgentState.running) {
                ctx.startService(Intent(ctx, AgentService::class.java).setAction(ACTION_RECONNECT))
            } else {
                start(ctx)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AgentService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var locator: AdbdLocator
    private lateinit var cm: ConnectivityManager

    @Volatile private var running = false
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var beaconSocket: DatagramSocket? = null
    @Volatile private var lastAdbdPort: Int? = null

    /** One connection per computer, by bridge id. */
    private val conns = ConcurrentHashMap<String, BridgeConn>()

    private var ticker: ScheduledExecutorService? = null
    private var booster: RejoinBooster? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastRearmCheck = 0L
    private var lastRearm = 0L
    private var lastNotif = ""
    private var lastUi = 0L
    private val bindFailures = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        if (intent?.action == ACTION_STOP) {
            prefs.armed = false
            if (!running) startForegroundCompat("stopping") // satisfy the startForegroundService contract
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT && running) {
            syncConns()
            return START_STICKY
        }
        if (!running) startAgent()
        return START_STICKY
    }

    private fun startAgent() {
        running = true
        AgentState.running = true
        prefs.armed = true
        locator = AdbdLocator(this)
        // App-specific external storage: `adb shell` can read it (see `adbw logs`), and it needs
        // no permission. Falls back to private storage if external storage isn't mounted.
        AgentState.logFile = java.io.File(getExternalFilesDir(null) ?: filesDir, "agent.log")
        locator.lastGood = prefs.lastAdbdPort
        cm = getSystemService(ConnectivityManager::class.java)
        startForegroundCompat("starting")
        val names = prefs.bridges.joinToString { it.label }
        AgentState.log("agent ${versionName()} started; computers: ${names.ifEmpty { "none yet" }}")

        val wm = getSystemService(WifiManager::class.java)
        // LOW_LATENCY disables Wi-Fi power save while we're foreground with the screen on.
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "adbw").apply { acquire() }
        multicastLock = wm.createMulticastLock("adbw").apply { setReferenceCounted(false); acquire() }
        if (prefs.keepAwake) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbw:agent").apply { acquire() }
        }

        // Hold Wi-Fi up. On Wear OS this is what stops the watch from turning Wi-Fi off while it's
        // connected to the phone over Bluetooth. No INTERNET capability: a LAN without internet
        // access is fine for us.
        cm.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            wifiCallback,
        )

        if (prefs.boostRejoin) booster = RejoinBooster(this).also { it.selfTest() }
        locator.start()
        ticker = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({
                try {
                    tick()
                } catch (t: Throwable) {
                    AgentState.log("tick: $t")
                }
            }, 200, 200, TimeUnit.MILLISECONDS)
        }
        syncConns()
        Thread(::beaconLoop, "adbw-beacon").apply { isDaemon = true }.start()
    }

    /** Make the running connections match the stored computers: start new ones, stop removed
     *  ones, and hand changed settings to the rest. Connections that didn't change are untouched,
     *  so setting up a second computer never disturbs a session on the first. */
    @Synchronized
    private fun syncConns() {
        val wanted = prefs.bridges.associateBy { it.bridgeId }
        for ((id, c) in conns) {
            val p = wanted[id]
            val old = c.profile
            if (p == null) {
                conns.remove(id)
                AgentState.log("${c.tag}removed: this device no longer follows that computer")
                // The command that removed it often arrived over this very link. Let its reply
                // get out before the link goes.
                ticker?.schedule({ c.stop("computer removed") }, 1500, TimeUnit.MILLISECONDS)
            } else if (p.deviceId != old.deviceId || p.key != old.key) {
                AgentState.log("${c.tag}new key from `adbw setup`; reconnecting")
                c.stop("re-keyed")
                conns[id] = BridgeConn(p).also { it.start() }
            } else if (p != old) {
                c.profile = p
                if (p.host != old.host || p.hostName != old.hostName) {
                    AgentState.log("${c.tag}address settings changed; reconnecting (the session resumes)")
                    c.reconnect()
                } else {
                    c.kick()
                }
            }
        }
        for ((id, p) in wanted) {
            if (!conns.containsKey(id)) {
                AgentState.log("[${p.label}] following this computer")
                conns[id] = BridgeConn(p).also { it.start() }
            }
        }
    }

    override fun onDestroy() {
        if (running) {
            running = false
            AgentState.running = false
            AgentState.status = "stopped"
            AgentState.detail = "stopped"
            // Network I/O is not allowed on the main thread: tell the bridges from a worker so
            // they release their sessions now instead of holding them for the grace period.
            val all = conns.values.toList()
            conns.clear()
            Thread { all.forEach { it.stopNow("agent stopped") } }.apply { start(); join(1000) }
            beaconSocket?.close()
            ticker?.shutdownNow()
            try { cm.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
            locator.stop()
            wifiLock?.release()
            multicastLock?.release()
            wakeLock?.let { if (it.isHeld) it.release() }
            AgentState.log("agent stopped")
        }
        super.onDestroy()
    }

    // --- Wi-Fi -----------------------------------------------------------------------------------
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = wifiNetwork
            wifiNetwork = network
            AgentState.log("Wi-Fi network available")
            lastRearm = 0
            for (c in conns.values) {
                // A link bound to the old network is dead; drop it now instead of waiting for
                // the 10 s timeout. The session survives and resumes on the new link.
                val bound = c.linkNetwork
                if (bound != null && bound != network && previous != network) c.link?.close()
                // Reconnect now, not after the current backoff sleep.
                c.kick()
            }
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) wifiNetwork = null
            AgentState.log("Wi-Fi network lost")
            for (c in conns.values) if (c.linkNetwork == network) c.link?.close()
        }
    }

    // --- One computer ----------------------------------------------------------------------------
    /** The link to one computer's bridge, and the adb session it carries. */
    private inner class BridgeConn(@Volatile var profile: BridgeProfile) {
        val id = profile.bridgeId
        val tag: String get() = "[${profile.label}] "

        @Volatile var link: Link? = null
        @Volatile var linkNetwork: Network? = null
        @Volatile var ready = false          // handshake done; frames may flow
        @Volatile var linkSince = 0L
        @Volatile var session: Session? = null
        @Volatile var beaconHost: InetSocketAddress? = null
        @Volatile var lastBeacon = 0L
        @Volatile var serial = ""
        @Volatile var state = "starting"
        @Volatile var adbdPort: Int? = null

        @Volatile private var stopped = false
        @Volatile private var kicked = false
        // Wakes the link loop early (Wi-Fi came back, a beacon arrived) instead of finishing its
        // backoff sleep.
        private val wake = Semaphore(0)
        private val loggedErrors = HashSet<String>() // link loop thread only
        private var lastPing = 0L     // ticker thread only
        private var lastStatus = 0L   // ticker thread only

        private val active: Boolean get() = running && !stopped

        private fun log(msg: String) = AgentState.log(tag + msg)

        fun start() {
            Thread(::loop, "adbw-link-$id").apply { isDaemon = true }.start()
        }

        fun kick() {
            kicked = true
            wake.release()
        }

        /** Drop the link so it reconnects with new settings. Never call socket I/O on the main thread. */
        fun reconnect() {
            val l = link
            Thread { l?.close() }.start()
            kick()
        }

        fun stop(reason: String) {
            Thread { stopNow(reason) }.start()
        }

        fun stopNow(reason: String) {
            stopped = true
            session?.abort(reason)
            link?.close()
            wake.release()
        }

        /**
         * Where this bridge might be, best first: a pinned host if set; otherwise its latest
         * beacon, the last address that worked, then the computer's mDNS name. Unresolved on
         * purpose: names are looked up at connect time, on the link thread.
         */
        private fun hostCandidates(): List<Pair<String, Int>> {
            fun parse(s: String?): Pair<String, Int>? {
                if (s.isNullOrBlank()) return null
                val port = if (s.contains(':')) s.substringAfterLast(':').toIntOrNull() ?: Proto.DEFAULT_PORT
                else Proto.DEFAULT_PORT
                return s.substringBeforeLast(':', s) to port
            }
            val p = profile
            parse(p.host)?.let { return listOf(it) }
            val out = LinkedHashSet<Pair<String, Int>>()
            beaconHost?.let { out.add(it.hostString to it.port) }
            parse(p.lastHost)?.let(out::add)
            parse(p.hostName)?.let(out::add)
            return out.toList()
        }

        private fun loop() {
            var backoff = 250L
            var failures = 0
            var rejects = 0
            var downSince = SystemClock.elapsedRealtime()
            while (active) {
                val hosts = hostCandidates()
                if (hosts.isEmpty()) {
                    state = "waiting for `adbw run` on that computer"
                    wake.tryAcquire(1000, TimeUnit.MILLISECONDS)
                    continue
                }
                val (hostStr, hostPort) = hosts[failures % hosts.size]
                var l: Link? = null
                var rejected = false
                var wasUp = false
                try {
                    val target = InetSocketAddress(hostStr, hostPort) // resolves DNS / mDNS names
                    if (target.isUnresolved) throw IOException("can't resolve $hostStr")
                    // Prefer a socket bound to Wi-Fi (on a watch that keeps the link off Bluetooth).
                    // If that fails for any reason (Android 17 refuses the bind with EPERM; the
                    // emulator routes the host differently), retry at once on the default network,
                    // and stop trying to bind after a few failures in a row.
                    state = "connecting to $hostStr"
                    var net = if (prefs.bindWifi && bindFailures.get() < 3) wifiNetwork else null
                    var sock: Socket
                    try {
                        sock = net?.socketFactory?.createSocket() ?: Socket()
                        sock.tcpNoDelay = true
                        sock.keepAlive = true
                        sock.connect(target, 4000)
                        if (net != null) bindFailures.set(0)
                    } catch (e: IOException) {
                        if (net == null) throw e
                        if (bindFailures.incrementAndGet() == 3) {
                            AgentState.log("Wi-Fi-bound sockets keep failing (${e.message}); using the default network")
                        }
                        net = null
                        sock = Socket()
                        sock.tcpNoDelay = true
                        sock.keepAlive = true
                        sock.connect(target, 4000)
                    }
                    sock.soTimeout = Proto.LINK_TIMEOUT_MS
                    l = Link(sock, "${target.hostString}:${target.port}")
                    link = l
                    linkNetwork = net
                    if (!active) throw IOException("stopped")

                    val s = session
                    val p = profile
                    val nonceA = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                        .joinToString("") { "%02x".format(it) }
                    l.sendJson(Proto.HELLO, JSONObject()
                        .put("v", Proto.VERSION)
                        .put("device", p.deviceId)
                        .put("nonce", nonceA))
                    val ch = l.read()
                    if (ch.type == Proto.REJECT) {
                        rejected = true
                        throw IOException("bridge refused: " + ch.json().optString("error"))
                    }
                    if (ch.type != Proto.CHALLENGE) throw IOException("expected CHALLENGE, got ${ch.type}")
                    val nonceB = ch.json().getString("nonce")
                    val expected = linkMac(p.key, "bridge", nonceA, nonceB)
                    if (!java.security.MessageDigest.isEqual(
                            expected.toByteArray(), ch.json().optString("mac").toByteArray())) {
                        rejected = true
                        throw IOException("that bridge doesn't have this device's key (another " +
                            "computer at this address, or rerun `adbw setup`)")
                    }
                    l.sendJson(Proto.AUTH, JSONObject()
                        .put("mac", linkMac(p.key, "agent", nonceA, nonceB))
                        .put("name", deviceName())
                        .put("model", Build.MODEL)
                        .put("sdk", Build.VERSION.SDK_INT)
                        .put("agent", versionName())
                        .put("session", s?.sid ?: JSONObject.NULL)
                        .put("rx", s?.rx ?: 0L))
                    val w = l.read()
                    if (w.type == Proto.REJECT) {
                        rejected = true
                        throw IOException("bridge refused: " + w.json().optString("error"))
                    }
                    if (w.type != Proto.WELCOME) throw IOException("expected WELCOME, got ${w.type}")
                    val wj = w.json()
                    serial = wj.optString("serial")
                    if (s != null) {
                        if (wj.optBoolean("resume")) {
                            s.attach(l, wj.getLong("rx"))
                            AgentState.resumes++
                            log("link up to ${l.label}; resumed session ${s.sid}")
                        } else {
                            s.finish()
                            log("link up to ${l.label}; bridge dropped old session")
                        }
                    } else {
                        log("link up to ${l.label} (adb serial there: $serial)" + if (net != null) " via Wi-Fi" else "")
                    }
                    val at = "${target.hostString}:${target.port}"
                    prefs.updateBridge(id) { it.copy(lastHost = at, lastSeen = System.currentTimeMillis()) }
                        ?.let { stored -> profile = stored }
                    AgentState.linkUps++
                    linkSince = SystemClock.elapsedRealtime()
                    lastStatus = 0
                    backoff = 250
                    failures = 0
                    rejects = 0
                    loggedErrors.clear()
                    wasUp = true
                    ready = true
                    while (active) handle(l, l.read())
                } catch (e: Exception) {
                    failures++
                    // Log a failure once per address and kind until the link is next up, not on
                    // every retry: a computer that is away would otherwise fill the log.
                    if (loggedErrors.size > 16) loggedErrors.clear()
                    if (active && loggedErrors.add("$hostStr:$hostPort ${e.javaClass.simpleName}")) {
                        log("link to $hostStr:$hostPort: ${e.javaClass.simpleName}: ${e.message}")
                    }
                } finally {
                    ready = false
                    l?.close()
                    if (link === l) link = null
                    session?.detach()
                }
                if (!active) break
                val now = SystemClock.elapsedRealtime()
                if (wasUp) downSince = now
                var wait = backoff
                if (rejected) {
                    // A refusal won't fix itself in 250 ms. Try the other addresses first (this
                    // one may belong to a different computer now), then back off for real.
                    rejects++
                    val rounds = rejects / hosts.size
                    wait = if (rejects % hosts.size != 0) 500
                    else (8000L shl (rounds - 1).coerceIn(0, 3)).coerceAtMost(60_000)
                }
                state = if (rejected) "refused by that computer; rerun `adbw setup` there"
                else "looking for it"
                val woken = kicked || wake.tryAcquire(wait, TimeUnit.MILLISECONDS)
                wake.drainPermits()
                kicked = false
                if (woken) {
                    backoff = 250
                    failures = 0
                    rejects = 0
                    downSince = SystemClock.elapsedRealtime()
                } else {
                    // A computer that has been away for minutes (you took the device home, or the
                    // laptop to work) is checked less often. Its beacon or a Wi-Fi change wakes
                    // this loop at once.
                    val cap = if (now - downSince > 120_000) 15_000L else 4000L
                    backoff = (backoff * 2).coerceAtMost(cap)
                }
            }
            state = "stopped"
        }

        private fun handle(l: Link, f: Frame) {
            when (f.type) {
                Proto.DATA -> session?.onData(f.u64(), f.payload, 8)
                Proto.ACK -> session?.onAck(f.u64())
                Proto.PING -> l.send(Proto.PONG, f.payload)
                Proto.PONG -> l.rttMs = System.currentTimeMillis() - f.u64()
                Proto.OPEN -> openSession(l, f.json().getString("session"))
                Proto.CLOSE -> {
                    val j = f.json()
                    val s = session
                    if (s != null && s.sid == j.getString("session")) {
                        s.onClose(j.getLong("final"), j.optBoolean("abort"))
                    }
                }
            }
        }

        private fun openSession(l: Link, sid: String) {
            session?.abort("replaced by new session")
            val candidates = locator.candidates(profile.adbdPort)
            var sock: Socket? = null
            var port = -1
            for (p in candidates) {
                try {
                    val s = Socket() // never bound to Wi-Fi: this is loopback
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), p), 1500)
                    sock = s
                    port = p
                    break
                } catch (_: IOException) {
                }
            }
            if (sock == null) {
                val err = "no adbd port reachable on loopback (tried $candidates). Is Wireless debugging on?"
                log(err)
                l.sendJson(Proto.OPEN_FAIL, JSONObject().put("session", sid).put("error", err))
                return
            }
            locator.lastGood = port
            prefs.lastAdbdPort = port
            lastAdbdPort = port
            adbdPort = port
            val s = Session(sid, sock, "127.0.0.1:$port", { log(it) }) { done ->
                if (session === done) session = null
            }
            session = s
            l.sendJson(Proto.OPEN_OK, JSONObject().put("session", sid).put("target", "127.0.0.1:$port"))
            s.attach(l, 0)
            s.start()
            AgentState.sessions++
            log("session $sid -> adbd 127.0.0.1:$port")
        }

        fun tick(now: Long) {
            val l = link ?: return
            if (!ready || l.closed) return
            val s = session
            if (s != null && s.link === l && s.rx != s.rxAcked) s.sendAck()
            if (now - lastPing >= Proto.PING_MS) {
                l.sendU64(Proto.PING, System.currentTimeMillis())
                lastPing = now
            }
            if (now - lastStatus >= 5000) {
                l.sendJson(Proto.STATUS, statusJson(this))
                lastStatus = now
            }
        }

        fun describe(now: Long): String {
            val l = link
            if (!ready || l == null) return state
            val s = session
            return "UP ${l.label} ${(now - linkSince) / 1000}s rtt ${l.rttMs}ms" +
                (s?.let { "\n    session ${it.sid}, unacked ${it.unacked}B" } ?: "")
        }
    }

    // --- Bridge discovery ------------------------------------------------------------------------
    private fun beaconLoop() {
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(Proto.DEFAULT_PORT))
                beaconSocket = s
                val buf = ByteArray(256)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    // "ADBW2 <port> <bridge id> <hostname>"
                    val parts = String(p.data, 0, p.length).split(' ')
                    if (parts.size < 3 || parts[0] != "ADBW2") continue
                    val c = conns[parts[2]] ?: continue // not one of this device's computers
                    val addr = InetSocketAddress(p.address, parts[1].toIntOrNull() ?: continue)
                    val now = SystemClock.elapsedRealtime()
                    val moved = addr != c.beaconHost
                    // "Back in view" after a silence also counts: the computer just arrived.
                    val fresh = moved || now - c.lastBeacon > 10_000
                    c.beaconHost = addr
                    c.lastBeacon = now
                    if (moved) AgentState.log("${c.tag}beacon from ${p.address.hostAddress}:${parts[1]}")
                    if (fresh && !c.ready) c.kick()
                }
            }
        } catch (e: Exception) {
            if (running) AgentState.log("beacon listener stopped: ${e.message}")
        }
    }

    // --- Ticker ----------------------------------------------------------------------------------
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        for (c in conns.values) c.tick(now)
        booster?.tick(now, wifiNetwork != null)
        if (now - lastRearmCheck >= 3000) {
            lastRearmCheck = now
            maybeRearm(now)
        }
        if (now - lastUi >= 1000) {
            lastUi = now
            updateStatus(now)
        }
    }

    /** Android turns Wireless debugging off on every BSSID change; turn it back on. Only if some
     *  computer reaches this device through Wireless debugging rather than a fixed legacy port. */
    private fun maybeRearm(now: Long) {
        if (!prefs.rearm || conns.values.none { it.profile.adbdPort == null }) return
        if (!WirelessDebugging.canWrite(this) || WirelessDebugging.isEnabled(this)) return
        if (wifiNetwork == null || now - lastRearm < 15_000) return
        lastRearm = now
        if (WirelessDebugging.enable(this)) {
            AgentState.log("Wireless debugging was off; turned it back on")
            ticker?.schedule({
                if (!WirelessDebugging.isEnabled(this)) {
                    AgentState.log("Android switched it off again: this access point isn't trusted yet. " +
                        "Tap 'Always allow on this network' on the device once.")
                }
            }, 2, TimeUnit.SECONDS)
        }
    }

    @Suppress("DEPRECATION")
    private fun statusJson(c: BridgeConn): JSONObject {
        val info = getSystemService(WifiManager::class.java).connectionInfo
        return JSONObject()
            .put("adbd", (c.adbdPort ?: lastAdbdPort)?.let { "127.0.0.1:$it" } ?: "?")
            .put("wireless_debugging", WirelessDebugging.isEnabled(this))
            .put("secure_settings", WirelessDebugging.canWrite(this))
            .put("wifi_held", wifiNetwork != null)
            .put("rssi_dbm", (info.rssi / 10) * 10) // coarse, so the bridge log isn't noisy
            .put("sdk", Build.VERSION.SDK_INT)
            .put("computers", conns.size)
    }

    private fun updateStatus(now: Long) {
        val list = conns.values.sortedBy { it.profile.label }
        val up = list.count { it.ready }
        val busy = list.count { it.ready && it.session != null }
        val text = buildString {
            if (list.isEmpty()) appendLine("not set up: run `adbw setup` on your computer")
            for (c in list) appendLine("${c.profile.label}: ${c.describe(now)}")
            val fixed = list.mapNotNull { it.profile.adbdPort }.distinct()
            appendLine("adbd port: " + (lastAdbdPort ?: locator.nsdPort ?: fixed.firstOrNull() ?: "?"))
            appendLine("Wireless debugging: ${if (WirelessDebugging.isEnabled(this@AgentService)) "on" else "off"}" +
                "   auto re-enable: ${if (WirelessDebugging.canWrite(this@AgentService)) "ready" else "needs grant"}")
            appendLine("Wi-Fi held: ${wifiNetwork != null}")
            append("links ${AgentState.linkUps}  resumes ${AgentState.resumes}  sessions ${AgentState.sessions}")
        }
        AgentState.status = text.lineSequence().first()
        AgentState.detail = text
        val notif = when {
            list.isEmpty() -> "Not set up: run adbw setup on your computer"
            list.size == 1 && up == 1 -> "Connected to ${list[0].profile.label}"
            list.size == 1 -> "Looking for ${list[0].profile.label}…"
            up == 0 -> "Looking for your ${list.size} computers…"
            else -> "Connected to $up of ${list.size} computers"
        } + if (busy > 0) " · adb active" else ""
        if (notif != lastNotif) {
            lastNotif = notif
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(notif))
        }
    }

    // --- Misc ------------------------------------------------------------------------------------
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun startForegroundCompat(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "adbw link", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("adbw agent")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
