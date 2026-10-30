package net.nuclearlighters.ventriloquist

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView

/** Status screen. OK on the remote asks for the capture consent when none is held. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            render()
            status.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0x1F, 0x29, 0x33))
            gravity = Gravity.CENTER
            val pad = (48 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isFocusable = true
        }
        setContentView(status)
        startForegroundService(
            Intent(this, VentriloquistService::class.java).setAction(VentriloquistService.ACTION_START),
        )
    }

    override fun onResume() {
        super.onResume()
        status.post(refresh)
    }

    override fun onPause() {
        status.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (VentriloquistService.instance?.status()?.get("projection_ready") != true) {
                startActivity(Intent(this, ConsentActivity::class.java))
            }
            render()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun render() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val s = VentriloquistService.instance?.status()
        status.text = if (s == null) {
            getString(R.string.status_starting, version)
        } else {
            @Suppress("UNCHECKED_CAST")
            val capture = s["capture"] as Map<String, Any?>
            getString(
                R.string.status_template,
                version,
                s["consent"].toString(),
                s["mode"].toString(),
                if (capture["running"] == true) getString(R.string.yes) else getString(R.string.no),
                s["hdmi_latency_ms"]?.toString() ?: "?",
                "${s["hdmi_volume"]} / ${s["hdmi_volume_max"]}",
                if (s["dirty"] == true) getString(R.string.yes) else getString(R.string.no),
                s["last_error"]?.toString() ?: "-",
            )
        }
    }
}
