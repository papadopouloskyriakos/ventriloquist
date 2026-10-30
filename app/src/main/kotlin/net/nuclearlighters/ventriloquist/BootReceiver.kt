package net.nuclearlighters.ventriloquist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * At boot, and after the app has been updated: start the idle service (which restores any
 * change a previous run left behind) and let it ask for the capture consent.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(VentriloquistService.TAG, "${intent.action}: starting the service")
                context.startForegroundService(
                    Intent(context, VentriloquistService::class.java)
                        .setAction(VentriloquistService.ACTION_BOOT),
                )
            }
        }
    }
}
