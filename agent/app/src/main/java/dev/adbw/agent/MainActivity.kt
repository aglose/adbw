package dev.adbw.agent

import android.Manifest
import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Deliberately plain views: runs unchanged on phones, tablets and round watches. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        applyExtras()
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val isWatch = packageManager.hasSystemFeature("android.hardware.type.watch")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Extra side/top padding keeps content inside a round watch face.
            val side = if (isWatch) pad * 2 else pad
            setPadding(side, if (isWatch) pad * 3 else pad, side, pad * 4)
        }
        fun label(text: String, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 2, 0, 0)
        }

        root.addView(label("adbw agent", 20f, bold = true))
        statusView = label("", 12f).apply { typeface = Typeface.MONOSPACE }
        root.addView(statusView)

        root.addView(label("Bridge host (blank = auto via beacon)"))
        hostEdit = EditText(this).apply {
            setSingleLine()
            hint = "192.168.1.20:7100"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.host)
        }
        root.addView(hostEdit)

        root.addView(label("adbd port (blank = auto: Wireless debugging)"))
        portEdit = EditText(this).apply {
            setSingleLine()
            hint = "auto, or 5555 after `adb tcpip 5555`"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.fixedPort?.toString() ?: "")
        }
        root.addView(portEdit)

        val rearm = CheckBox(this).apply {
            text = "Turn Wireless debugging back on after roams"
            isChecked = prefs.rearm
            setOnCheckedChangeListener { _, v -> prefs.rearm = v }
        }
        val awake = CheckBox(this).apply {
            text = "Keep CPU awake (most stable, uses battery)"
            isChecked = prefs.keepAwake
            setOnCheckedChangeListener { _, v -> prefs.keepAwake = v }
        }
        val bind = CheckBox(this).apply {
            text = "Send link over Wi-Fi (not Bluetooth/cellular)"
            isChecked = prefs.bindWifi
            setOnCheckedChangeListener { _, v -> prefs.bindWifi = v }
        }
        val boost = CheckBox(this).apply {
            text = "Rejoin Wi-Fi faster after drops (scans while disconnected)"
            isChecked = prefs.boostRejoin
            setOnCheckedChangeListener { _, v -> prefs.boostRejoin = v }
        }
        root.addView(rearm)
        root.addView(boost)
        root.addView(awake)
        root.addView(bind)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Start"
            setOnClickListener {
                save()
                val wanted = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
                    .filter { checkSelfPermission(it) != 0 }
                if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
                AgentService.applyConfig(this@MainActivity)
            }
        })
        buttons.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { AgentService.stop(this@MainActivity) }
        })
        root.addView(buttons)

        root.addView(label(
            "Set up from your computer, with this device connected to adb:\n" +
                "  adbw setup\n" +
                "then keep `adbw run` going there. The device reconnects by itself.\n" +
                "Faster Wi-Fi rejoin: Developer options > turn off Wi-Fi scan throttling.",
            11f,
        ).apply { typeface = Typeface.MONOSPACE })

        root.addView(label("Log", 13f, bold = true))
        logView = label("", 10f).apply { typeface = Typeface.MONOSPACE }
        root.addView(logView)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    /** `am start -n dev.adbw.agent/.MainActivity --ez start true` (or stop). Settings come only
     *  through [ConfigReceiver], which other apps can't call. */
    private fun applyExtras() {
        val x = intent?.extras ?: return
        when {
            x.getBoolean("stop") -> AgentService.stop(this)
            x.getBoolean("start") -> if (!AgentState.running) AgentService.start(this)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyExtras()
    }

    // What the fields showed when last loaded. Only a field the user actually edited is written
    // back, so this screen never overwrites settings `adbw setup` changed behind it.
    private var shownHost = ""
    private var shownPort = ""

    private fun loadFields() {
        shownHost = prefs.host
        shownPort = prefs.fixedPort?.toString() ?: ""
        hostEdit.setText(shownHost)
        portEdit.setText(shownPort)
    }

    private fun save() {
        val host = hostEdit.text.toString().trim()
        val port = portEdit.text.toString().trim()
        if (host != shownHost) prefs.host = host
        if (port != shownPort) prefs.fixedPort = port.toIntOrNull()
        shownHost = host
        shownPort = port
    }

    override fun onResume() {
        super.onResume()
        loadFields()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        save()
        super.onPause()
    }

    private fun render() {
        statusView.text = if (AgentState.running) AgentState.detail else buildString {
            appendLine("agent stopped")
            appendLine("Wireless debugging: ${if (WirelessDebugging.isEnabled(this@MainActivity)) "on" else "off"}")
            append("auto re-enable: ${if (WirelessDebugging.canWrite(this@MainActivity)) "ready" else "needs grant (see below)"}")
        }
        logView.text = AgentState.logText()
        logView.visibility = if (logView.text.isEmpty()) View.GONE else View.VISIBLE
    }
}
