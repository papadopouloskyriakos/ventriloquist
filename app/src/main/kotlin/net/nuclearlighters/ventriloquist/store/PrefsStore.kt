package net.nuclearlighters.ventriloquist.store

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/** [KeyValueStore] on SharedPreferences, written with commit() so it is on disk on return. */
class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        if (!editor.commit()) throw IOException("could not persist $key")
    }

    companion object {
        fun open(context: Context, name: String): PrefsStore =
            PrefsStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    }
}
