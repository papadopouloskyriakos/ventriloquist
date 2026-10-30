package net.nuclearlighters.ventriloquist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Obtains the playback-capture consent and hands it to the service, and gives the service its
 * background recording access.
 *
 * Android 11 and later let a foreground service record in the background only when it was
 * started while an activity of the app was visible. So every service start from here happens
 * while this (translucent) activity is on screen, and the activity stays up for a moment after
 * the hand-over so that the service enters the foreground (with the microphone type) while the
 * app is still visible. When a projection is already held, no dialog is shown: opening this
 * activity only re-arms the access (`adb shell am start -n <package>/.ConsentActivity`).
 */
class ConsentActivity : Activity() {

    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean(STATE_ASKED) ?: false
        if (asked) return
        asked = true
        if (VentriloquistService.instance?.projectionHeld == true) {
            startVentriloquist(VentriloquistService.ACTION_REARM)
            finishSoon()
            return
        }
        startVentriloquist(VentriloquistService.ACTION_START)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSION)
        } else {
            askProjection()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ASKED, asked)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSION) askProjection()
    }

    private fun askProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
        } catch (e: RuntimeException) {
            Log.e(VentriloquistService.TAG, "consent dialog unavailable", e)
            finish()
        }
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        val service = Intent(this, VentriloquistService::class.java)
        if (resultCode == RESULT_OK && data != null) {
            service.action = VentriloquistService.ACTION_PROJECTION
            service.putExtra(VentriloquistService.EXTRA_RESULT_CODE, resultCode)
            service.putExtra(VentriloquistService.EXTRA_DATA, data)
        } else {
            service.action = VentriloquistService.ACTION_CONSENT_DENIED
        }
        startForegroundService(service)
        finishSoon()
    }

    private fun startVentriloquist(action: String) {
        startForegroundService(Intent(this, VentriloquistService::class.java).setAction(action))
    }

    /** Stays visible while the service handles the start command, then goes. */
    private fun finishSoon() {
        window.decorView.postDelayed({ finish() }, VISIBLE_HOLD_MS)
    }

    companion object {
        private const val REQUEST_PERMISSION = 1
        private const val REQUEST_PROJECTION = 2
        private const val STATE_ASKED = "asked"
        private const val VISIBLE_HOLD_MS = 1500L
    }
}
