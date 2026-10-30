package net.nuclearlighters.ventriloquist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import net.nuclearlighters.ventriloquist.config.ConfigStore
import net.nuclearlighters.ventriloquist.util.Json
import java.nio.charset.StandardCharsets

/**
 * Bootstrap configuration over ADB, before any control token exists:
 *
 *     am broadcast -n <package>/.ConfigReceiver -a net.nuclearlighters.ventriloquist.action.CONFIGURE \
 *         --es json '{"control_token":"..."}'      (or --es json_b64 <base64 of the JSON>)
 *
 * The manifest protects this receiver with android.permission.DUMP, which the ADB shell holds
 * and ordinary apps cannot get, so only ADB (or root) can configure the app this way. The
 * result line of `am broadcast` carries "ok: ..." or "error: ...".
 */
class ConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIGURE) return
        var code = 0
        val message = try {
            val text = intent.getStringExtra(EXTRA_JSON)
                ?: intent.getStringExtra(EXTRA_JSON_B64)?.let {
                    String(Base64.decode(it, Base64.DEFAULT), StandardCharsets.UTF_8)
                }
                ?: throw IllegalArgumentException("extra '$EXTRA_JSON' or '$EXTRA_JSON_B64' is required")
            val update = Json.parseObject(text)
            ConfigStore.update(context, update)
            context.startForegroundService(
                Intent(context, VentriloquistService::class.java)
                    .setAction(VentriloquistService.ACTION_CONFIG_CHANGED),
            )
            "ok: updated ${update.keys.sorted().joinToString(",")}"
        } catch (e: Exception) {
            code = 1
            "error: ${e.message}"
        }
        Log.i(VentriloquistService.TAG, "configure broadcast: $message")
        if (isOrderedBroadcast) {
            resultCode = code
            resultData = message
        }
    }

    companion object {
        const val ACTION_CONFIGURE = "net.nuclearlighters.ventriloquist.action.CONFIGURE"
        const val EXTRA_JSON = "json"
        const val EXTRA_JSON_B64 = "json_b64"
    }
}
