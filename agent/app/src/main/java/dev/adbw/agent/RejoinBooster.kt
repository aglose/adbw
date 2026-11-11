package dev.adbw.agent

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Makes Android rejoin Wi-Fi sooner after a drop, especially with the screen off.
 *
 * Why it's slow (AOSP WifiConnectivityManager + Wi-Fi config defaults):
 *  - Screen off and disconnected, a stationary device relies on firmware PNO scans: every 60 s,
 *    tripling to 180 s after 3 tries.
 *  - PNO frequency culling scans only channels where saved networks were seen before, so an
 *    access point on another channel or band can be missed entirely.
 *
 * Why this helps: AllSingleScanListener runs network selection, and connects, on the results of
 * every single scan, "including the ones initiated by ... other modules". An app's full-band
 * startScan() is uncut by culling and gives auto-join a fresh chance.
 *
 * Budget: a foreground service counts as a foreground app, so ScanRequestProxy allows 4 scans per
 * 2 min (one every 30 s, still 6x better than 180 s PNO). With Developer options > "Wi-Fi scan
 * throttling" turned off there's no limit, and this scans every 5 s. It detects which applies
 * from startScan()'s result. It only runs while Wi-Fi is on but disconnected.
 */
class RejoinBooster(private val ctx: Context) {
    private val wm = ctx.getSystemService(WifiManager::class.java)
    private var downSince = 0L
    private var lastScan = 0L
    private var scans = 0
    private var refused = 0
    private var throttled = false
    private var throttledAt = 0L
    private var consecutiveRefusals = 0
    private var permissionWarned = false

    /**
     * startScan() reports permission problems as a plain `false` (WifiServiceImpl swallows the
     * SecurityException), which looks exactly like throttling. Check the real preconditions once
     * at startup and say what's missing.
     */
    fun selfTest() {
        val missing = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ).filter { ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        val problems = buildList {
            if (missing.isNotEmpty()) add("missing " + missing.joinToString { it.substringAfterLast('.') })
            if (!lm.isLocationEnabled) add("location is off")
        }
        if (problems.isEmpty()) {
            AgentState.log("rejoin booster ready (location permissions granted, location on)")
        } else {
            AgentState.log("rejoin booster CAN'T SCAN: ${problems.joinToString("; ")}. Fix: " +
                "adb shell pm grant ${ctx.packageName} android.permission.ACCESS_FINE_LOCATION && " +
                "adb shell pm grant ${ctx.packageName} android.permission.ACCESS_BACKGROUND_LOCATION")
        }
    }

    /** Called from the agent's ticker with whether a Wi-Fi network is currently connected. */
    fun tick(now: Long, wifiConnected: Boolean) {
        if (wifiConnected || !wm.isWifiEnabled) {
            if (downSince != 0L && wifiConnected) {
                AgentState.log(String.format("Wi-Fi rejoined after %.1fs (%d scans requested, %d refused)",
                    (now - downSince) / 1000.0, scans, refused))
            }
            downSince = 0
            return
        }
        if (downSince == 0L) {
            downSince = now
            lastScan = 0
            scans = 0
            refused = 0
            consecutiveRefusals = 0
            AgentState.log("Wi-Fi disconnected: requesting scans so Android rejoins sooner")
        }
        val down = now - downSince
        if (down < 2_000) return // Wi-Fi may still be coming up; scans are refused until it is
        if (throttled && now - throttledAt > 120_000) throttled = false // budget window passed; probe again
        val interval = when {
            throttled -> 30_000L          // stay inside 4 scans / 2 min
            down < 60_000 -> 5_000L        // throttling off: be aggressive at first
            down < 300_000 -> 10_000L
            else -> 30_000L
        }
        if (now - lastScan < interval) return
        lastScan = now
        val ok = try {
            @Suppress("DEPRECATION") // still the only way for an app to request a Wi-Fi scan
            wm.startScan()
        } catch (e: SecurityException) {
            if (!permissionWarned) {
                AgentState.log("Wi-Fi scan not allowed: grant NEARBY_WIFI_DEVICES (see setup)")
                permissionWarned = true
            }
            false
        }
        AgentState.log("  rejoin scan ${scans + refused + 1} at ${down / 1000}s: ${if (ok) "accepted" else "refused"}")
        if (ok) {
            scans++
            consecutiveRefusals = 0
        } else {
            refused++
            consecutiveRefusals++
            // One refusal can just mean scanning isn't ready; two in a row means throttling.
            if (consecutiveRefusals >= 2 && !throttled && !permissionWarned) {
                throttled = true
                throttledAt = now
                AgentState.log("Wi-Fi scans throttled (4 per 2 min); scanning every 30 s. " +
                    "Turn off Developer options > Wi-Fi scan throttling to rejoin faster.")
            }
        }
    }
}
