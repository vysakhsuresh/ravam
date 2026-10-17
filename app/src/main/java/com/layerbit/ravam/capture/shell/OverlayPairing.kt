package com.layerbit.ravam.capture.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A floating pairing panel drawn on top of everything, including the system's Wireless
 * Debugging dialog. This is the one flow that works reliably: the Settings pairing dialog
 * stays the foreground activity (so its pairing session stays alive), while the user types
 * the six-digit code into this overlay. The port is filled in automatically over mDNS.
 *
 * Needs SYSTEM_ALERT_WINDOW ("Display over other apps"), requested by the caller.
 */
object OverlayPairing {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var discovery: PairingDiscovery? = null
    private var host: String? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    val isShowing: Boolean get() = view != null

    @SuppressLint("SetTextI18n")
    fun show(context: Context) {
        if (view != null) return
        val app = context.applicationContext
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2101418.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(app).apply {
            text = "Pair Ravam"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val status = TextView(app).apply {
            text = "In Settings, open Wireless debugging → \"Pair device with pairing code\" " +
                "and keep it open. Then type the 6-digit code below."
            setTextColor(0xFFB0B8C0.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        val portField = EditText(app).apply {
            hint = "Port (auto)"
            setHintTextColor(0xFF808890.toInt())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val codeField = EditText(app).apply {
            hint = "6-digit code"
            setHintTextColor(0xFF808890.toInt())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val pairBtn = Button(app).apply { text = "Pair" }
        val closeBtn = Button(app).apply { text = "Close" }
        val buttons = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pairBtn)
            addView(closeBtn)
        }
        card.addView(title); card.addView(status); card.addView(portField)
        card.addView(codeField); card.addView(buttons)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (so the code field gets the keyboard) but not touch-modal, so taps
            // outside the card still reach the Settings dialog underneath.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        wm = windowManager
        view = card
        runCatching { windowManager.addView(card, params) }

        discovery = PairingDiscovery(app).also { disc ->
            disc.start { h, p ->
                scope.launch {
                    host = h
                    if (portField.text.isNullOrBlank()) portField.setText(p.toString())
                    status.text = "Pairing dialog found (port $p). Type the 6-digit code, tap Pair."
                }
            }
        }

        pairBtn.setOnClickListener {
            val port = portField.text.toString().trim().toIntOrNull()
            val code = codeField.text.toString().trim()
            if (port == null || code.isEmpty()) {
                status.text = "Enter the code (and the port, if it wasn't found automatically)."
                return@setOnClickListener
            }
            status.text = "Pairing…"
            scope.launch {
                val ok = EmbeddedAdb.pair(app, port, code, host)
                if (ok) {
                    val connected = EmbeddedAdb.connect(app)
                    status.text = if (connected) {
                        "Paired and connected. Privileged recording is ready — tap Close."
                    } else {
                        "Paired. Ravam will connect when a call starts — tap Close."
                    }
                } else {
                    status.text = "Couldn't pair: ${EmbeddedAdb.lastError ?: "unknown"}. " +
                        "Open a fresh pairing code and try again."
                }
            }
        }
        closeBtn.setOnClickListener { hide() }
    }

    fun hide() {
        discovery?.stop(); discovery = null
        val v = view; val w = wm
        if (v != null && w != null) runCatching { w.removeView(v) }
        view = null; wm = null; host = null
    }
}
