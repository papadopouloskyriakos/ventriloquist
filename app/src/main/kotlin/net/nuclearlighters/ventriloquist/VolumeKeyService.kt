package net.nuclearlighters.ventriloquist

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Takes the remote's volume buttons while the home speakers play and turns them into speaker
 * volume; at any other time the buttons pass through untouched. An accessibility service is
 * the only place an app sees a volume key before the system acts on it. It reads no screen
 * content and asks for no events. Enabled once over adb:
 * `settings put secure enabled_accessibility_services <package>/<package>.VolumeKeyService`
 * and `settings put secure accessibility_enabled 1`.
 *
 * When the app is not running, nothing is taken, so the buttons always work for the TV.
 */
class VolumeKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        connected = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val steps = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        val service = VentriloquistService.instance ?: return false
        if (!service.takesVolumeKeys()) return false
        // both the press and the release are taken, so the system never sees half a press;
        // every press and every key repeat while held is one step
        if (event.action == KeyEvent.ACTION_DOWN) service.volumeKey(steps)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var connected = false
            private set
    }
}
