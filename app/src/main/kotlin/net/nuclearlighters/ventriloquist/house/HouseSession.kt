package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.ma.MaApi
import net.nuclearlighters.ventriloquist.shield.ShieldModule
import net.nuclearlighters.ventriloquist.store.Ledger

/** Capture plus VBAN sending; started and stopped by the house session. */
interface Streamer {
    val running: Boolean

    /** Starts capture and sending to the configured target; does nothing when already running. */
    fun start(config: Config)

    fun stop()
}

/**
 * The house-mode sequences.
 *
 * Start: surround output PCM; the box's media output muted (when `mute_box`), so the TV
 * receives silence; capture and VBAN on; the source played on the configured player.
 * Stop: stop that player, stop capture, then put the box back (the TV sound returns last).
 *
 * The playing player is recorded in [maLedger] before it is told to play, like the box's
 * changes in the shield module, so [stop] also cleans up after a crash. Not thread-safe: call
 * from one thread.
 */
class HouseSession(
    private val shield: ShieldModule,
    private val maLedger: Ledger,
    private val maFactory: (Config) -> MaApi,
    private val streamer: Streamer,
) {
    var running = false
        private set

    var target: String? = null
        private set

    var lastMaError: String? = null
        private set

    val maDirty: Boolean get() = maLedger.dirty

    fun maOriginals(): Map<String, Any?> = maLedger.snapshot()

    /** Starts house mode. On failure everything is put back and the exception is rethrown. */
    fun start(config: Config) {
        val missing = config.missingForHouse()
        if (missing.isNotEmpty()) throw IllegalStateException("not configured: ${missing.joinToString(", ")}")
        if (running) return
        val ma = maFactory(config)
        try {
            shield.setSurroundPcm(true)
            if (config.muteBox) shield.setBoxMuted(true)
            streamer.start(config)
            maLedger.put(K_PLAYING, config.playerId)
            ma.playMedia(config.playerId, config.sourceUri)
            running = true
            target = config.playerId
            lastMaError = null
        } catch (e: Exception) {
            lastMaError = "${e.javaClass.simpleName}: ${e.message}"
            stop(config)
            throw e
        }
    }

    /**
     * Stops house mode, or cleans up what a crashed run left behind. Best effort throughout: a
     * step that fails stays recorded for the next attempt and is reported. Returns the failures.
     */
    fun stop(config: Config): List<String> {
        val errors = mutableListOf<String>()
        (maLedger.get(K_PLAYING) as? String)?.let { player ->
            if (config.maUrl.isEmpty()) {
                errors.add("stop $player: ma_url not configured")
            } else {
                try {
                    maFactory(config).stop(player)
                    maLedger.clear(K_PLAYING)
                } catch (e: Exception) {
                    errors.add("stop $player: ${e.message}")
                }
            }
        }
        // anything else an older version recorded (film-mode player settings) is dropped
        for (name in maLedger.names().filter { it != K_PLAYING }) maLedger.clear(name)
        try {
            streamer.stop()
        } catch (e: Exception) {
            errors.add("streamer: ${e.message}")
        }
        errors += shield.restore()
        running = false
        target = null
        if (errors.isNotEmpty()) lastMaError = errors.joinToString("; ")
        return errors
    }

    /**
     * Moves the playing player's volume by [steps] volume steps (negative is down), within
     * 0 and `max_volume`; [known] is a level read or set moments ago, saving a round trip.
     * Returns the new level, or null when house mode is not running.
     */
    fun nudgeVolume(steps: Int, config: Config, known: Int?): Int? {
        val player = target ?: return null
        if (!running) return null
        if (steps == 0) return known
        val ma = maFactory(config)
        val base = known ?: ma.volume(player) ?: return null
        val next = VolumeMath.next(base, steps, config.volumeStep, config.maxVolume)
        if (next != base) ma.setGroupVolume(player, next)
        return next
    }

    companion object {
        const val K_PLAYING = "playing"
    }
}

/** The volume buttons' arithmetic, kept pure for tests. */
object VolumeMath {
    /**
     * The level after [steps] presses of [step] points from [base]. Up never goes past [max]
     * (and never lowers a level someone set above it); down stops at 0.
     */
    fun next(base: Int, steps: Int, step: Int, max: Int): Int {
        val wanted = base + steps * step
        return if (steps > 0) {
            minOf(wanted, maxOf(base, max)).coerceIn(0, 100)
        } else {
            wanted.coerceIn(0, 100)
        }
    }
}
