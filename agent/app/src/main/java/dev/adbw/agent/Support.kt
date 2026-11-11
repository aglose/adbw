package dev.adbw.agent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One computer this device follows: that computer's bridge, plus the identity and link key this
 * device has there. `adbw setup` on each computer adds or updates its own profile.
 */
data class BridgeProfile(
    val bridgeId: String,
    val deviceId: String,
    val key: String,
    val name: String = "",       // the computer's name, for display only
    val hostName: String = "",   // its mDNS name ("name.local:port"), tried when all else fails
    val host: String = "",       // pinned "host:port"; blank = discover by beacon
    val lastHost: String = "",   // the address that last worked
    val adbdPort: Int? = null,   // fixed adbd port for this computer; null = Wireless debugging
    val lastSeen: Long = 0,      // wall-clock ms of the last successful link
) {
    val label: String
        get() = name.ifBlank { hostName.substringBefore('.').substringBefore(':').ifBlank { bridgeId } }

    fun toJson(): JSONObject = JSONObject()
        .put("bridge_id", bridgeId).put("device_id", deviceId).put("key", key)
        .put("name", name).put("host_name", hostName).put("host", host)
        .put("last_host", lastHost).put("adbd_port", adbdPort ?: 0).put("last_seen", lastSeen)

    companion object {
        fun fromJson(j: JSONObject) = BridgeProfile(
            bridgeId = j.getString("bridge_id"),
            deviceId = j.getString("device_id"),
            key = j.getString("key"),
            name = j.optString("name"),
            hostName = j.optString("host_name"),
            host = j.optString("host"),
            lastHost = j.optString("last_host"),
            adbdPort = j.optInt("adbd_port").takeIf { it > 0 },
            lastSeen = j.optLong("last_seen"),
        )
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("adbw", Context.MODE_PRIVATE)

    companion object {
        /** More than anyone needs; a cap so a device can't collect profiles without bound. */
        const val MAX_BRIDGES = 8
        private val lock = Any()
    }

    /** The computers this device follows. */
    var bridges: List<BridgeProfile>
        get() = synchronized(lock) { readBridges() }
        set(v) = synchronized(lock) { writeBridges(v) }

    private fun readBridges(): List<BridgeProfile> {
        val raw = sp.getString("bridges", null)
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { BridgeProfile.fromJson(arr.getJSONObject(it)) }
            } catch (e: JSONException) {
                emptyList()
            }
        }
        // Agents before 0.3 followed one computer, stored as separate settings. Carry it over.
        val key = sp.getString("key", "") ?: ""
        val deviceId = sp.getString("device_id", "") ?: ""
        if (key.isBlank() || deviceId.isBlank()) return emptyList()
        val migrated = listOf(BridgeProfile(
            bridgeId = sp.getString("bridge_id", "")?.takeIf { it.isNotBlank() } ?: "default",
            deviceId = deviceId,
            key = key,
            hostName = sp.getString("host_name", "") ?: "",
            host = sp.getString("host", "") ?: "",
            lastHost = sp.getString("last_host", "") ?: "",
            adbdPort = sp.getString("fixed_port", "")?.toIntOrNull(),
        ))
        writeBridges(migrated)
        return migrated
    }

    private fun writeBridges(v: List<BridgeProfile>) {
        val arr = JSONArray()
        v.forEach { arr.put(it.toJson()) }
        sp.edit().putString("bridges", arr.toString()).apply()
    }

    fun bridge(id: String): BridgeProfile? = bridges.firstOrNull { it.bridgeId == id }

    /** Add or replace a computer. Beyond the cap, the one not seen for longest makes room. */
    fun upsertBridge(p: BridgeProfile) = synchronized(lock) {
        var all = readBridges().filter { it.bridgeId != p.bridgeId } + p
        while (all.size > MAX_BRIDGES) {
            val oldest = all.filter { it.bridgeId != p.bridgeId }.minByOrNull { it.lastSeen } ?: break
            all = all - oldest
        }
        writeBridges(all)
    }

    /** Change one stored profile; returns the new value, or null if that computer was removed. */
    fun updateBridge(id: String, change: (BridgeProfile) -> BridgeProfile): BridgeProfile? =
        synchronized(lock) {
            val all = readBridges()
            val old = all.firstOrNull { it.bridgeId == id } ?: return@synchronized null
            val new = change(old)
            if (new != old) writeBridges(all.map { if (it.bridgeId == id) new else it })
            new
        }

    fun removeBridge(id: String) = synchronized(lock) {
        writeBridges(readBridges().filter { it.bridgeId != id })
    }

    /** The adbd port that last worked, kept across restarts. */
    var lastAdbdPort: Int?
        get() = sp.getInt("last_adbd_port", 0).takeIf { it > 0 }
        set(v) = sp.edit().putInt("last_adbd_port", v ?: 0).apply()

    var armed: Boolean
        get() = sp.getBoolean("armed", false)
        set(v) = sp.edit().putBoolean("armed", v).apply()

    var rearm: Boolean
        get() = sp.getBoolean("rearm", true)
        set(v) = sp.edit().putBoolean("rearm", v).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(v) = sp.edit().putBoolean("keep_awake", v).apply()

    var boostRejoin: Boolean
        get() = sp.getBoolean("boost_rejoin", true)
        set(v) = sp.edit().putBoolean("boost_rejoin", v).apply()

    var bindWifi: Boolean
        get() = sp.getBoolean("bind_wifi", true)
        set(v) = sp.edit().putBoolean("bind_wifi", v).apply()
}

/** Process-wide state the UI polls. */
object AgentState {
    @Volatile var running = false
    @Volatile var status: String = "stopped"
    @Volatile var detail: String = "stopped"   // multi-line status for the UI
    @Volatile var linkUps = 0
    @Volatile var resumes = 0
    @Volatile var sessions = 0

    private val logs = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Persistent copy of the log (logcat's ring buffer can be as small as 256 KiB). Read it with
     *  `adbw logs <device>`. */
    @Volatile var logFile: java.io.File? = null

    fun log(msg: String) {
        Log.i("adbw", msg)
        val line = "${fmt.format(Date())} $msg"
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > 80) logs.removeFirst()
            logFile?.let { f ->
                try {
                    if (f.length() > 1_000_000) f.renameTo(java.io.File(f.path + ".1"))
                    f.appendText(line + "\n")
                } catch (_: Exception) {
                }
            }
        }
    }

    // toList().asReversed(): List.reversed() resolves to a Java 21 method missing before API 35.
    fun logText(): String = synchronized(logs) { logs.toList().asReversed().joinToString("\n") }
}

object WirelessDebugging {
    private const val KEY = "adb_wifi_enabled" // Settings.Global.ADB_WIFI_ENABLED (hidden constant)

    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, KEY, 0) == 1

    fun enable(ctx: Context): Boolean = try {
        Settings.Global.putInt(ctx.contentResolver, KEY, 1)
    } catch (e: SecurityException) {
        false
    }
}

/**
 * Finds the port adbd listens on, from the device itself:
 *  1. a port fixed at setup (legacy `adb tcpip` devices)
 *  2. system properties (service.adb.tls.port for Wireless debugging; tcp ports for `adb tcpip`)
 *  3. on-device mDNS: adbd advertises _adb-tls-connect._tcp (Wireless debugging) or _adb._tcp
 *     (legacy); keep the ones that resolve to this device
 *  4. the last port that worked, then 5555
 * Connection refused on loopback is instant, so the caller just tries candidates in order.
 */
class AdbdLocator(ctx: Context) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var nsdPort: Int? = null
        private set
    @Volatile var nsdLegacyPort: Int? = null
        private set
    @Volatile var lastGood: Int? = null

    private val discovery = listener(tls = true)
    private val legacyDiscovery = listener(tls = false)

    private fun listener(tls: Boolean) = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) = resolve(info, 0, tls)
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            AgentState.log("mDNS discovery failed to start ($errorCode)")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
            nsd.discoverServices("_adb._tcp", NsdManager.PROTOCOL_DNS_SD, legacyDiscovery)
        } catch (e: Exception) {
            AgentState.log("mDNS discovery unavailable: ${e.message}")
        }
    }

    fun stop() {
        try {
            nsd.stopServiceDiscovery(discovery)
        } catch (_: Exception) {
        }
        try {
            nsd.stopServiceDiscovery(legacyDiscovery)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION") // resolveService/host: the replacements need API 34; this is fine.
    private fun resolve(info: NsdServiceInfo, attempt: Int, tls: Boolean) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    if (attempt < 5) main.postDelayed({ resolve(info, attempt + 1, tls) }, 400L * (attempt + 1))
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    val h = si.host ?: return
                    if (!isLocal(h)) return // another device's adbd on the same LAN
                    if (tls) {
                        if (nsdPort != si.port) AgentState.log("adbd TLS port from mDNS: ${si.port}")
                        nsdPort = si.port
                    } else {
                        if (nsdLegacyPort != si.port) AgentState.log("adbd legacy port from mDNS: ${si.port}")
                        nsdLegacyPort = si.port
                    }
                }
            })
        } catch (e: Exception) {
            if (attempt < 5) main.postDelayed({ resolve(info, attempt + 1, tls) }, 400L * (attempt + 1))
        }
    }

    fun candidates(fixed: Int?): List<Int> {
        val out = LinkedHashSet<Int>()
        fixed?.let(out::add) // tried first; the rest are fallbacks if that port moved
        sysprop("service.adb.tls.port")?.let(out::add)
        nsdPort?.let(out::add)
        lastGood?.let(out::add)
        nsdLegacyPort?.let(out::add)
        sysprop("service.adb.tcp.port")?.let(out::add)
        sysprop("persist.adb.tcp.port")?.let(out::add)
        out.add(5555)
        return out.filter { it in 1..65535 }
    }

    companion object {
        fun isLocal(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress) return true
            val want = addr.hostAddress?.substringBefore('%') ?: return false
            return try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .flatMap { it.inetAddresses.toList() }
                    .any { it.hostAddress?.substringBefore('%') == want }
            } catch (e: Exception) {
                false
            }
        }

        private val denied = java.util.Collections.synchronizedSet(HashSet<String>())

        /** Reads a system property; SELinux may deny some (e.g. adbd_prop on Android 17), in which
         *  case this returns null and remembers the denial so we never retry or fork getprop again. */
        fun sysprop(key: String): Int? {
            if (key in denied) return null
            val v = syspropUncached(key)
            if (v == null) denied += key
            return v
        }

        private fun syspropUncached(key: String): Int? {
            val viaReflection = try {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, key) as? String
            } catch (e: Throwable) {
                null
            }
            viaReflection?.toIntOrNull()?.let { if (it > 0) return it }
            return try {
                val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
                if (!p.waitFor(1, TimeUnit.SECONDS)) {
                    p.destroy(); null
                } else {
                    p.inputStream.bufferedReader().readText().trim().toIntOrNull()?.takeIf { it > 0 }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * How `adbw setup` configures the agent:
 *   adb shell am broadcast --include-stopped-packages -n dev.adbw.agent/.ConfigReceiver \
 *       --es bridge_id ... --es device_id ... --es key ...
 * Each computer has its own profile, keyed by its bridge id; setting one up leaves the others
 * alone. `--ez only true` drops every other computer, `--es remove_bridge ID` drops one.
 *
 * The manifest requires the sender to hold android.permission.DUMP, which the adb shell has and
 * ordinary apps don't, so another app on the device can't point the agent at a different bridge.
 *
 * Replies with data "ok" and, as the result code, how many computers the device now follows.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val x = intent.extras ?: return
        val prefs = Prefs(context)
        x.getString("remove_bridge")?.let { prefs.removeBridge(it) }
        val id = x.getString("bridge_id")
        if (id != null) {
            val old = prefs.bridge(id)
            val deviceId = x.getString("device_id") ?: old?.deviceId
            val key = x.getString("key") ?: old?.key
            if (deviceId.isNullOrBlank() || key.isNullOrBlank()) {
                resultData = "error: a new computer needs device_id and key"
                return
            }
            val base = old ?: BridgeProfile(id, deviceId, key)
            val profile = base.copy(
                deviceId = deviceId,
                key = key,
                name = x.getString("bridge_name") ?: base.name,
                hostName = x.getString("host_name") ?: base.hostName,
                host = x.getString("host")?.let { if (it == "auto") "" else it.trim() } ?: base.host,
                lastHost = x.getString("host_hint") ?: base.lastHost,
                adbdPort = if (x.containsKey("adbd_port")) x.getString("adbd_port")?.toIntOrNull()
                else base.adbdPort,
            )
            if (x.getBoolean("only")) prefs.bridges = listOf(profile) else prefs.upsertBridge(profile)
        }
        if (x.containsKey("rearm")) prefs.rearm = x.getBoolean("rearm")
        if (x.containsKey("keep_awake")) prefs.keepAwake = x.getBoolean("keep_awake")
        if (x.containsKey("bind_wifi")) prefs.bindWifi = x.getBoolean("bind_wifi")
        if (x.containsKey("boost_rejoin")) prefs.boostRejoin = x.getBoolean("boost_rejoin")
        if (AgentState.running) AgentService.applyConfig(context) // pick up the changes now
        resultCode = prefs.bridges.size
        resultData = "ok"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (Prefs(context).armed) AgentService.start(context)
        }
    }
}
