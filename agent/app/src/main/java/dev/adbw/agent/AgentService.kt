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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps a resumable link from this device to the adbw bridge and relays
 * the bridge's sessions into adbd over loopback.
 *
 * Threads: link loop (connect / read frames), beacon listener (UDP), one pump per session
 * (adbd -> bridge), and a 200 ms ticker (ACKs, PINGs, status, Wireless-debugging re-arm).
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

        /** Start, or if already running, drop the link so it reconnects with the new settings. */
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
    @Volatile private var link: Link? = null
    @Volatile private var linkNetwork: Network? = null
    @Volatile private var linkSince = 0L
    @Volatile private var session: Session? = null
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var beaconHost: InetSocketAddress? = null
    @Volatile private var beaconSocket: DatagramSocket? = null
    @Volatile private var lastAdbdPort: Int? = null
    @Volatile private var serial: String = ""

    private var ticker: ScheduledExecutorService? = null
    private var booster: RejoinBooster? = null
    // Wakes the link loop early (e.g. the moment Wi-Fi comes back) instead of finishing its backoff.
    private val linkKick = java.util.concurrent.Semaphore(0)
    @Volatile private var resetBackoff = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastPing = 0L
    private var lastStatus = 0L
    private var lastRearmCheck = 0L
    private var lastRearm = 0L
    private var lastNotif = ""
    private var lastUi = 0L
    @Volatile private var lastLinkError = ""

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
            AgentState.log("settings changed; reconnecting (session resumes if the bridge is the same)")
            val l = link
            Thread { l?.close() }.start()
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
        AgentState.log("agent started (device ${prefs.deviceId.take(8)})")

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
        Thread(::linkLoop, "adbw-link").apply { isDaemon = true }.start()
        Thread(::beaconLoop, "adbw-beacon").apply { isDaemon = true }.start()
        ticker = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({
                try {
                    tick()
                } catch (t: Throwable) {
                    AgentState.log("tick: $t")
                }
            }, 200, 200, TimeUnit.MILLISECONDS)
        }
    }

    override fun onDestroy() {
        if (running) {
            running = false
            AgentState.running = false
            AgentState.status = "stopped"
            AgentState.detail = "stopped"
            // Network I/O is not allowed on the main thread: tell the bridge from a worker so it
            // releases the session now instead of holding it for its grace period.
            val s = session
            val l = link
            Thread {
                s?.abort("agent stopped")
                l?.close()
            }.apply { start(); join(1000) }
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
            // The link is bound to the old network; drop it now instead of waiting for the
            // 10 s timeout. The session survives and resumes on the new link.
            val l = link
            if (l != null && linkNetwork != null && linkNetwork != network && previous != network) l.close()
            lastRearm = 0
            // Reconnect to the bridge now, not after the current backoff sleep.
            resetBackoff = true
            linkKick.release()
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) wifiNetwork = null
            AgentState.log("Wi-Fi network lost")
            if (linkNetwork == network) link?.close()
        }
    }

    // --- Link ------------------------------------------------------------------------------------
    /**
     * Where the bridge might be, best first: a pinned host if set; otherwise the bridge's latest
     * beacon, the last address that worked, then the computer's mDNS name. Unresolved on purpose:
     * names are looked up at connect time, on the link thread.
     */
    private fun hostCandidates(): List<Pair<String, Int>> {
        fun parse(s: String?): Pair<String, Int>? {
            if (s.isNullOrBlank()) return null
            val port = if (s.contains(':')) s.substringAfterLast(':').toIntOrNull() ?: Proto.DEFAULT_PORT
            else Proto.DEFAULT_PORT
            return s.substringBeforeLast(':', s) to port
        }
        parse(prefs.host)?.let { return listOf(it) }
        val out = LinkedHashSet<Pair<String, Int>>()
        beaconHost?.let { out.add(it.hostString to it.port) }
        parse(prefs.lastHost)?.let(out::add)
        parse(prefs.hostName)?.let(out::add)
        return out.toList()
    }

    private fun linkLoop() {
        var backoff = 250L
        var failures = 0
        while (running) {
            if (prefs.key.isBlank()) {
                AgentState.status = "not set up: run `adbw setup` on your computer"
                linkKick.tryAcquire(2000, TimeUnit.MILLISECONDS)
                continue
            }
            val hosts = hostCandidates()
            if (hosts.isEmpty()) {
                AgentState.status = "waiting for bridge: is `adbw run` running on your computer?"
                linkKick.tryAcquire(1000, TimeUnit.MILLISECONDS)
                continue
            }
            val (hostStr, hostPort) = hosts[failures % hosts.size]
            var l: Link? = null
            var rejected = false
            try {
                val target = InetSocketAddress(hostStr, hostPort) // resolves DNS / mDNS names
                if (target.isUnresolved) throw IOException("can't resolve $hostStr")
                // Alternate Wi-Fi-bound and default-network sockets when connects keep failing
                // (some networks, like the emulator's, route the host differently).
                val net = if (prefs.bindWifi && failures % 2 == 0) wifiNetwork else null
                val sock = net?.socketFactory?.createSocket() ?: Socket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                AgentState.status = "connecting to $target"
                sock.connect(target, 4000)
                sock.soTimeout = Proto.LINK_TIMEOUT_MS
                l = Link(sock, "$target")
                link = l
                linkNetwork = net

                val s = session
                val key = prefs.key
                val nonceA = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it) }
                l.sendJson(Proto.HELLO, JSONObject()
                    .put("v", Proto.VERSION)
                    .put("device", prefs.deviceId)
                    .put("nonce", nonceA))
                val ch = l.read()
                if (ch.type == Proto.REJECT) {
                    rejected = true
                    throw IOException("bridge refused: " + ch.json().optString("error"))
                }
                if (ch.type != Proto.CHALLENGE) throw IOException("expected CHALLENGE, got ${ch.type}")
                val nonceB = ch.json().getString("nonce")
                val expected = linkMac(key, "bridge", nonceA, nonceB)
                if (!java.security.MessageDigest.isEqual(
                        expected.toByteArray(), ch.json().optString("mac").toByteArray())) {
                    rejected = true
                    throw IOException("that bridge doesn't have this device's key (someone else's " +
                        "computer, or rerun `adbw setup`)")
                }
                l.sendJson(Proto.AUTH, JSONObject()
                    .put("mac", linkMac(key, "agent", nonceA, nonceB))
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
                        AgentState.log("link up to $target; resumed session ${s.sid}")
                    } else {
                        s.finish()
                        AgentState.log("link up to $target; bridge dropped old session")
                    }
                } else {
                    AgentState.log("link up to $target (adb serial $serial)" + if (net != null) " via Wi-Fi" else "")
                }
                prefs.lastHost = "${target.hostString}:${target.port}"
                AgentState.linkUps++
                linkSince = SystemClock.elapsedRealtime()
                lastStatus = 0
                backoff = 250
                failures = 0
                lastLinkError = ""
                while (running) handle(l, l.read())
            } catch (e: Exception) {
                failures++
                // Log a failure once per host and kind, not on every retry.
                val kind = "$hostStr:$hostPort ${e.javaClass.simpleName}"
                if (running && kind != lastLinkError) {
                    AgentState.log("link to $hostStr:$hostPort: ${e.javaClass.simpleName}: ${e.message}")
                }
                lastLinkError = kind
                // A refusal won't fix itself in 250 ms; don't hammer the bridge.
                if (rejected) backoff = 8000
            } finally {
                l?.close()
                if (link === l) link = null
                session?.detach()
            }
            if (!running) break
            AgentState.status = "link down, retrying in ${backoff}ms"
            linkKick.drainPermits()
            val kicked = linkKick.tryAcquire(backoff, TimeUnit.MILLISECONDS)
            backoff = if (kicked || resetBackoff) 250 else (backoff * 2).coerceAtMost(4000)
            if (kicked || resetBackoff) failures = 0
            resetBackoff = false
        }
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
        val candidates = locator.candidates(prefs.fixedPort)
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
            AgentState.log(err)
            l.sendJson(Proto.OPEN_FAIL, JSONObject().put("session", sid).put("error", err))
            return
        }
        locator.lastGood = port
        prefs.lastAdbdPort = port
        lastAdbdPort = port
        val s = Session(sid, sock, "127.0.0.1:$port", AgentState::log) { done ->
            if (session === done) session = null
        }
        session = s
        l.sendJson(Proto.OPEN_OK, JSONObject().put("session", sid).put("target", "127.0.0.1:$port"))
        s.attach(l, 0)
        s.start()
        AgentState.sessions++
        AgentState.log("session $sid -> adbd 127.0.0.1:$port")
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
                    if (parts.size >= 3 && parts[0] == "ADBW2") {
                        val want = prefs.bridgeId
                        if (want.isNotEmpty() && parts[2] != want) continue // someone else's bridge
                        val addr = InetSocketAddress(p.address, parts[1].toIntOrNull() ?: continue)
                        if (addr != beaconHost) {
                            beaconHost = addr
                            AgentState.log("bridge beacon: ${p.address.hostAddress}:${parts[1]} (${parts.getOrNull(3)})")
                            if (link == null) {
                                resetBackoff = true
                                linkKick.release()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) AgentState.log("beacon listener stopped: ${e.message}")
        }
    }

    // --- Ticker ----------------------------------------------------------------------------------
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val l = link
        val s = session
        if (l != null && !l.closed) {
            if (s != null && s.link === l && s.rx != s.rxAcked) s.sendAck()
            if (now - lastPing >= Proto.PING_MS) {
                l.sendU64(Proto.PING, System.currentTimeMillis())
                lastPing = now
            }
            if (now - lastStatus >= 5000) {
                l.sendJson(Proto.STATUS, statusJson())
                lastStatus = now
            }
        }
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

    /** Android turns Wireless debugging off on every BSSID change; turn it back on. */
    private fun maybeRearm(now: Long) {
        if (!prefs.rearm || prefs.fixedPort != null) return
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
    private fun statusJson(): JSONObject {
        val info = getSystemService(WifiManager::class.java).connectionInfo
        return JSONObject()
            .put("adbd", lastAdbdPort?.let { "127.0.0.1:$it" } ?: "?")
            .put("wireless_debugging", WirelessDebugging.isEnabled(this))
            .put("secure_settings", WirelessDebugging.canWrite(this))
            .put("wifi_held", wifiNetwork != null)
            .put("rssi_dbm", (info.rssi / 10) * 10) // coarse, so the bridge log isn't noisy
            .put("sdk", Build.VERSION.SDK_INT)
    }

    private fun updateStatus(now: Long) {
        val l = link
        val s = session
        val linkLine = if (l != null && !l.closed) {
            "link UP to ${l.label} for ${(now - linkSince) / 1000}s, rtt ${l.rttMs}ms"
        } else {
            AgentState.status.takeIf { it.startsWith("link") || it.startsWith("connect") || it.startsWith("waiting") }
                ?: "link down"
        }
        val text = buildString {
            appendLine(linkLine)
            appendLine("adb serial on host: ${serial.ifEmpty { "-" }}")
            appendLine("session: " + (s?.let { "${it.sid} -> ${it.target}, unacked ${it.unacked}B" } ?: "none"))
            appendLine("adbd port: " + (lastAdbdPort ?: locator.nsdPort ?: prefs.fixedPort ?: "?"))
            appendLine("Wireless debugging: ${if (WirelessDebugging.isEnabled(this@AgentService)) "on" else "off"}" +
                "   auto re-enable: ${if (WirelessDebugging.canWrite(this@AgentService)) "ready" else "needs grant"}")
            appendLine("Wi-Fi held: ${wifiNetwork != null}")
            append("links ${AgentState.linkUps}  resumes ${AgentState.resumes}  sessions ${AgentState.sessions}")
        }
        if (l != null && !l.closed) AgentState.status = text.lineSequence().first()
        AgentState.detail = text
        val notif = if (l != null && !l.closed) "Connected to bridge" + (s?.let { " · session active" } ?: "")
        else "Reconnecting to bridge…"
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
