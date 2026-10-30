package net.nuclearlighters.ventriloquist.store

import net.nuclearlighters.ventriloquist.util.Json
import java.io.IOException

/** Durable string storage. [put] must have reached storage when it returns, or throw. */
interface KeyValueStore {
    fun get(key: String): String?

    @Throws(IOException::class)
    fun put(key: String, value: String?)
}

/** In-memory store for tests and for callers that do not need durability. */
class MemoryStore : KeyValueStore {
    val values = HashMap<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * Records the original value of everything Ventriloquist changes, before it changes it, so
 * that a stop, a reboot or the next start after a crash can put it back.
 *
 * The whole ledger is one JSON object under one key, written synchronously on every change,
 * so a record is never half written. The ledger is dirty while it holds any entry.
 * A recorded value may be JSON null (for example a setting that was unset).
 */
class Ledger(private val store: KeyValueStore, private val key: String) {

    private val entries: LinkedHashMap<String, Any?> = load()

    val dirty: Boolean
        @Synchronized get() = entries.isNotEmpty()

    @Synchronized
    fun has(name: String): Boolean = entries.containsKey(name)

    @Synchronized
    fun get(name: String): Any? = entries[name]

    @Synchronized
    fun names(): List<String> = entries.keys.toList()

    @Synchronized
    fun snapshot(): Map<String, Any?> = LinkedHashMap(entries)

    /**
     * Records [value] as the original of [name] unless an original is already recorded (the
     * first original wins: it is the state before Ventriloquist touched anything). Returns
     * true when it recorded. Throws when the record could not be persisted; the caller must
     * then not make the change.
     */
    @Synchronized
    @Throws(IOException::class)
    fun recordOriginal(name: String, value: Any?): Boolean {
        if (entries.containsKey(name)) return false
        entries[name] = value
        try {
            persist()
        } catch (e: IOException) {
            entries.remove(name)
            throw e
        }
        return true
    }

    /** Sets [name] whatever it held before (for markers rather than originals). */
    @Synchronized
    @Throws(IOException::class)
    fun put(name: String, value: Any?) {
        val had = entries.containsKey(name)
        val previous = entries[name]
        entries[name] = value
        try {
            persist()
        } catch (e: IOException) {
            if (had) entries[name] = previous else entries.remove(name)
            throw e
        }
    }

    /** Forgets [name] after its original has been put back. */
    @Synchronized
    @Throws(IOException::class)
    fun clear(name: String) {
        if (!entries.containsKey(name)) return
        val previous = entries.remove(name)
        try {
            persist()
        } catch (e: IOException) {
            entries[name] = previous
            throw e
        }
    }

    private fun persist() {
        store.put(key, if (entries.isEmpty()) null else Json.write(entries))
    }

    private fun load(): LinkedHashMap<String, Any?> {
        val text = store.get(key) ?: return LinkedHashMap()
        return try {
            LinkedHashMap(Json.parseObject(text))
        } catch (e: IllegalArgumentException) {
            LinkedHashMap()
        }
    }
}
