package net.nuclearlighters.ventriloquist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot hook. The skeleton only logs; starting the idle service and re-arming capture consent
 * at boot belong to the control milestone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(VentriloquistService.TAG, "boot completed; nothing to start yet")
        }
    }
}
