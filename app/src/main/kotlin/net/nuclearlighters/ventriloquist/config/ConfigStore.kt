package net.nuclearlighters.ventriloquist.config

import android.content.Context
import android.util.Log
import net.nuclearlighters.ventriloquist.store.PrefsStore
import net.nuclearlighters.ventriloquist.util.Json

/**
 * The process-wide configuration: loaded once from SharedPreferences, updated by the ADB
 * broadcast and by `POST /config`, read by everything else. Readers always see a complete
 * [Config] (the reference is swapped atomically).
 */
object ConfigStore {
    private const val TAG = "Ventriloquist"
    private const val FILE = "config"
    private const val KEY = "json"

    @Volatile
    private var cached: Config? = null

    fun get(context: Context): Config {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val store = PrefsStore.open(context.applicationContext, FILE)
            val loaded = store.get(KEY)?.let { text ->
                try {
                    Config().merge(Config.migrate(Json.parseObject(text)))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "stored configuration rejected, using defaults: ${e.message}")
                    null
                }
            } ?: Config()
            cached = loaded
            return loaded
        }
    }

    /** Applies [update], persists it, and returns the new configuration. */
    fun update(context: Context, update: Map<String, Any?>): Config = synchronized(this) {
        val merged = get(context).merge(update)
        val store = PrefsStore.open(context.applicationContext, FILE)
        store.put(KEY, Json.write(merged.toMap(redactSecrets = false)))
        cached = merged
        merged
    }
}
