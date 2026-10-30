package net.nuclearlighters.ventriloquist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Obtains the playback-capture consent and hands it to the service. Starts the service first:
 * the foreground service of type mediaProjection must be running before the projection is
 * created. Asks for RECORD_AUDIO too when it has not been granted over ADB.
 */
class ConsentActivity : Activity() {

    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean(STATE_ASKED) ?: false
        startForegroundService(
            Intent(this, VentriloquistService::class.java).setAction(VentriloquistService.ACTION_START),
        )
        if (asked) return
        asked = true
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
        startService(service)
        finish()
    }

    companion object {
        private const val REQUEST_PERMISSION = 1
        private const val REQUEST_PROJECTION = 2
        private const val STATE_ASKED = "asked"
    }
}
