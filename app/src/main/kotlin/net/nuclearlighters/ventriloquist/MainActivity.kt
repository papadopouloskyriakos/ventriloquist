package net.nuclearlighters.ventriloquist

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView

/** Status screen. OK on the remote starts or stops the idle service. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0x1F, 0x29, 0x33))
            gravity = Gravity.CENTER
            val pad = (48 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isFocusable = true
        }
        setContentView(status)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            toggleService()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun toggleService() {
        val intent = Intent(this, VentriloquistService::class.java)
        if (VentriloquistService.running) {
            intent.action = VentriloquistService.ACTION_STOP
            startService(intent)
        } else {
            intent.action = VentriloquistService.ACTION_START
            startForegroundService(intent)
        }
        status.postDelayed({ render() }, 300)
    }

    private fun render() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val state = getString(
            if (VentriloquistService.running) R.string.state_running else R.string.state_stopped,
        )
        status.text = getString(R.string.status_template, version, state)
    }
}
