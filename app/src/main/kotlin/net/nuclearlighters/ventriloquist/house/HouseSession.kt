package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.ma.MaApi
import net.nuclearlighters.ventriloquist.ma.MaClient
import net.nuclearlighters.ventriloquist.shield.LatencyModule
import net.nuclearlighters.ventriloquist.store.Ledger

enum class HouseMode { FILM, MUSIC }

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
 * Film: save `output_channels` and `sync_adjust` of every surround player, set them to
 * `surround` and the configured sync adjustment; HDMI latency to `film_latency_ms`; TV muted;
 * surround output PCM; capture and VBAN on; play the source on the film player.
 * Music: the same without the channel change, with latency 0 (no picture to wait for), on the
 * music player. Stop: stop the playing player, stop capture, put the players' settings back,
 * then the Shield's.
 *
 * Every Music Assistant change is recorded in [maLedger] before it is made (and the playing
 * player as a marker), like the Shield's in the latency module, so [stop] also cleans up after
 * a crash. Not thread-safe: call from one thread.
 */
class HouseSession(
    private val shield: LatencyModule,
    private val maLedger: Ledger,
    private val maFactory: (Config) -> MaApi,
    private val streamer: Streamer,
) {
    var mode: HouseMode? = null
        private set

    var target: String? = null
        private set

    var lastMaError: String? = null
        private set

    val maDirty: Boolean get() = maLedger.dirty

    fun maOriginals(): Map<String, Any?> = maLedger.snapshot()

    /**
     * Starts [newMode], or switches to it from the other mode (the Shield side stays as it is,
     * the Music Assistant side moves to the other player). On failure everything is put back
     * and the exception is rethrown.
     */
    fun start(newMode: HouseMode, config: Config) {
        val missing = config.missingForHouse(film = newMode == HouseMode.FILM)
        if (missing.isNotEmpty()) throw IllegalStateException("not configured: ${missing.joinToString(", ")}")
        if (mode == newMode) return
        val ma = maFactory(config)
        try {
            if (mode != null) leaveMusicAssistant(ma)
            if (newMode == HouseMode.FILM) {
                for (player in config.surroundPlayerIds) {
                    val current = ma.getPlayerValues(player, CHANNEL_KEYS)
                    val absent = CHANNEL_KEYS.filter { it !in current }
                    if (absent.isNotEmpty()) {
                        throw IllegalStateException("player $player has no ${absent.joinToString(", ")}")
                    }
                    maLedger.recordOriginal(PLAYER_PREFIX + player, current)
                    ma.savePlayerValues(
                        player,
                        mapOf(
                            MaClient.OUTPUT_CHANNELS to SURROUND,
                            MaClient.SYNC_ADJUST to config.surroundSyncAdjustMs,
                        ),
                    )
                }
            }
            shield.setLatency(if (newMode == HouseMode.FILM) config.filmLatencyMs else 0)
            // a box that turns its volume into infrared commands to the TV (the Shield does)
            // cannot give the old level back exactly, so muting is a per-device choice
            if (config.tvMute) shield.setTvMuted(true)
            shield.setSurroundPcm(true)
            streamer.start(config)
            val player = if (newMode == HouseMode.FILM) config.filmPlayerId else config.musicPlayerId
            maLedger.put(K_PLAYING, player)
            ma.playMedia(player, config.sourceUri)
            mode = newMode
            target = player
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
        val ma: MaApi? = if (config.maUrl.isNotEmpty()) {
            try {
                maFactory(config)
            } catch (e: Exception) {
                errors.add("music assistant: ${e.message}")
                null
            }
        } else {
            null
        }
        (maLedger.get(K_PLAYING) as? String)?.let { player ->
            if (ma == null) {
                errors.add("stop $player: ma_url not configured")
            } else {
                try {
                    ma.stop(player)
                    maLedger.clear(K_PLAYING)
                } catch (e: Exception) {
                    errors.add("stop $player: ${e.message}")
                }
            }
        }
        try {
            streamer.stop()
        } catch (e: Exception) {
            errors.add("streamer: ${e.message}")
        }
        if (ma != null) {
            errors += restorePlayers(ma)
        } else if (maLedger.names().any { it.startsWith(PLAYER_PREFIX) }) {
            errors.add("player settings: ma_url not configured")
        }
        errors += shield.restore()
        mode = null
        target = null
        if (errors.isNotEmpty()) lastMaError = errors.joinToString("; ")
        return errors
    }

    /** Applies a new film latency while film mode runs; returns the read-back, or null when idle. */
    fun applyFilmLatency(ms: Int): LatencyModule.LatencyReading? =
        if (mode == HouseMode.FILM) shield.setLatency(ms) else null

    private fun leaveMusicAssistant(ma: MaApi) {
        (maLedger.get(K_PLAYING) as? String)?.let { player ->
            ma.stop(player)
            maLedger.clear(K_PLAYING)
        }
        val errors = restorePlayers(ma)
        if (errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("; "))
    }

    private fun restorePlayers(ma: MaApi): List<String> {
        val errors = mutableListOf<String>()
        for (name in maLedger.names().filter { it.startsWith(PLAYER_PREFIX) }) {
            val player = name.removePrefix(PLAYER_PREFIX)
            @Suppress("UNCHECKED_CAST")
            val values = maLedger.get(name) as? Map<String, Any?>
            try {
                if (values != null && values.isNotEmpty()) ma.savePlayerValues(player, values)
                maLedger.clear(name)
            } catch (e: Exception) {
                errors.add("restore $player: ${e.message}")
            }
        }
        return errors
    }

    companion object {
        const val SURROUND = "surround"
        const val K_PLAYING = "playing"
        const val PLAYER_PREFIX = "player:"
        val CHANNEL_KEYS = listOf(MaClient.OUTPUT_CHANNELS, MaClient.SYNC_ADJUST)
    }
}
