package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.config.Config

/** Monotonic milliseconds for durations, local wall-clock minute of the day for quiet hours. */
interface HouseClock {
    fun nowMs(): Long
    fun minuteOfDay(): Int
}

enum class ModeOverride { AUTO, OFF, FILM, MUSIC }

enum class Activity { PLAYING, PAUSED, IDLE }

data class Observation(
    val activity: Activity,
    val kind: HouseMode,
    val packageName: String? = null,
)

sealed class Action {
    data class Start(val mode: HouseMode) : Action()
    data class Switch(val mode: HouseMode) : Action()
    object Stop : Action() {
        override fun toString() = "Stop"
    }
}

/** The durations and quiet hours the machine needs, taken from [Config]. */
data class Timing(
    val debounceMs: Long,
    val stopDelayMs: Long,
    val quietStartMinute: Int,
    val quietEndMinute: Int,
) {
    companion object {
        fun of(c: Config) = Timing(
            c.startDebounceS * 1000L, c.stopDelayS * 1000L, c.quietStartMinute, c.quietEndMinute,
        )
    }
}

/**
 * Decides when house mode starts, switches and stops. Pure: inputs are fed in, [evaluate]
 * returns the next action and assumes it succeeds; [startFailed] reports otherwise.
 *
 * Automatic rules: start once playback has been running for the debounce time, with the screen
 * on, outside quiet hours; stay through a pause; stop when playback has ended, or the screen
 * has been off, for the stop delay; switch film and music when the kind of playback changes
 * (debounced the same way). Nothing starts or switches inside quiet hours; a running mode
 * finishes normally. Overrides: `off` stops at once; `film` or `music` run that mode until the
 * override changes, and are refused inside quiet hours; a manual mode ends (back to `auto`) when
 * the screen has been off for the stop delay. After a failed start the machine waits
 * [RETRY_MS] before trying again, or until playback ends and starts anew.
 */
class HouseStateMachine(private val clock: HouseClock) {

    var override: ModeOverride = ModeOverride.AUTO
        private set

    var running: HouseMode? = null
        private set

    var observation = Observation(Activity.IDLE, HouseMode.FILM)
        private set

    var screenOn = true
        private set

    /** Why a wanted start is not happening, for the status page; null when nothing waits. */
    var blocked: String? = null
        private set

    private var playingSince: Long? = null
    private var kindSince: Long? = null
    private var idleSince: Long? = null
    private var screenOffSince: Long? = null
    private var retryAt = 0L

    fun observe(o: Observation) {
        val now = clock.nowMs()
        val previous = observation
        if (o.activity == Activity.PLAYING) {
            if (previous.activity != Activity.PLAYING || playingSince == null) playingSince = now
            if (previous.activity != Activity.PLAYING || previous.kind != o.kind || kindSince == null) {
                kindSince = now
            }
        } else {
            playingSince = null
            kindSince = null
        }
        if (o.activity == Activity.IDLE) {
            if (idleSince == null) idleSince = now
            retryAt = 0 // a new playback gets a fresh attempt
        } else {
            idleSince = null
        }
        observation = o
    }

    fun screen(on: Boolean) {
        if (on) {
            screenOffSince = null
        } else if (screenOffSince == null) {
            screenOffSince = clock.nowMs()
        }
        screenOn = on
    }

    fun inQuietHours(t: Timing): Boolean {
        val m = clock.minuteOfDay()
        val s = t.quietStartMinute
        val e = t.quietEndMinute
        return when {
            s == e -> false
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    /** Sets the override; returns false (and changes nothing) when a manual start is refused. */
    fun setOverride(o: ModeOverride, t: Timing): Boolean {
        val wanted = manualMode(o)
        if (wanted != null && running != wanted && inQuietHours(t)) return false
        override = o
        retryAt = 0
        // Stop delays count from the operator's change, not from long before it.
        val now = clock.nowMs()
        if (idleSince != null) idleSince = now
        if (screenOffSince != null) screenOffSince = now
        return true
    }

    /**
     * The next action. [blockedFor] names a reason a mode cannot start right now (not configured,
     * no capture consent or access); such a start waits, reported in [blocked], with no attempt
     * and no back-off, and goes ahead as soon as the reason is gone.
     */
    fun evaluate(t: Timing, blockedFor: (HouseMode) -> String? = { null }): Action? {
        val now = clock.nowMs()
        val quiet = inQuietHours(t)
        blocked = null
        when (override) {
            ModeOverride.OFF -> return if (running != null) stop() else null
            ModeOverride.FILM, ModeOverride.MUSIC -> {
                val wanted = manualMode(override)!!
                val off = screenOffSince
                if (!screenOn && off != null && now - off >= t.stopDelayMs) {
                    override = ModeOverride.AUTO
                    return if (running != null) stop() else null
                }
                if (running == wanted) return null
                if (quiet) return block("quiet hours")
                if (now < retryAt) return block("waiting after a failed start")
                blockedFor(wanted)?.let { return block(it) }
                return if (running == null) start(wanted) else switch(wanted)
            }
            ModeOverride.AUTO -> {
                val current = running
                if (current == null) {
                    val since = playingSince
                    if (observation.activity != Activity.PLAYING || since == null) return null
                    if (now - since < t.debounceMs) return null
                    if (!screenOn) return block("screen off")
                    if (quiet) return block("quiet hours")
                    if (now < retryAt) return block("waiting after a failed start")
                    blockedFor(observation.kind)?.let { return block(it) }
                    return start(observation.kind)
                }
                val idle = idleSince
                val off = screenOffSince
                val idleDue = observation.activity == Activity.IDLE && idle != null && now - idle >= t.stopDelayMs
                val screenDue = !screenOn && off != null && now - off >= t.stopDelayMs
                if (idleDue || screenDue) return stop()
                val kindAge = kindSince
                if (observation.activity == Activity.PLAYING && observation.kind != current &&
                    kindAge != null && now - kindAge >= t.debounceMs
                ) {
                    if (quiet) return block("quiet hours")
                    if (!screenOn) return null
                    blockedFor(observation.kind)?.let { return block(it) }
                    return switch(observation.kind)
                }
                return null
            }
        }
    }

    /** The last Start or Switch failed; everything was put back. */
    fun startFailed() {
        running = null
        retryAt = clock.nowMs() + RETRY_MS
        if (manualMode(override) != null) override = ModeOverride.AUTO
    }

    /** House mode ended for an outside reason (consent lost, a restore request). */
    fun stopped() {
        running = null
    }

    private fun start(mode: HouseMode): Action {
        running = mode
        return Action.Start(mode)
    }

    private fun switch(mode: HouseMode): Action {
        running = mode
        return Action.Switch(mode)
    }

    private fun stop(): Action {
        running = null
        return Action.Stop
    }

    private fun block(reason: String): Action? {
        blocked = reason
        return null
    }

    companion object {
        const val RETRY_MS = 5 * 60 * 1000L

        fun manualMode(o: ModeOverride): HouseMode? = when (o) {
            ModeOverride.FILM -> HouseMode.FILM
            ModeOverride.MUSIC -> HouseMode.MUSIC
            else -> null
        }
    }
}

/** Turns what the platform reports about playback into an [Observation]. */
object PlaybackClassifier {

    enum class SessionState { PLAYING, PAUSED, OTHER }

    enum class Content { MOVIE, MUSIC, SPEECH, UNKNOWN }

    data class Session(val packageName: String, val state: SessionState)

    /** One audio player: whether its usage is MEDIA or GAME, its content type, and whether it
     *  is known to be started (null when the platform does not say). */
    data class Player(val media: Boolean, val content: Content, val started: Boolean?)

    fun observe(sessions: List<Session>, players: List<Player>, config: Config): Observation {
        val playing = sessions.firstOrNull { it.state == SessionState.PLAYING }
        val paused = sessions.firstOrNull { it.state == SessionState.PAUSED }
        val media = players.filter { it.media }
        val content = when {
            media.any { it.content == Content.MOVIE } -> Content.MOVIE
            media.any { it.content == Content.MUSIC } -> Content.MUSIC
            media.any { it.content == Content.SPEECH } -> Content.SPEECH
            else -> Content.UNKNOWN
        }
        val activity = when {
            playing != null -> Activity.PLAYING
            media.any { it.started == true } -> Activity.PLAYING
            paused != null && media.isNotEmpty() -> Activity.PAUSED
            else -> Activity.IDLE
        }
        val pkg = (playing ?: paused)?.packageName
        return Observation(activity, classify(pkg, content, config), pkg)
    }

    /** Film for a listed film app; music for a listed music app or music content; else film. */
    fun classify(packageName: String?, content: Content, config: Config): HouseMode = when {
        packageName != null && packageName in config.filmPackages -> HouseMode.FILM
        packageName != null && packageName in config.musicPackages -> HouseMode.MUSIC
        content == Content.MUSIC -> HouseMode.MUSIC
        else -> HouseMode.FILM
    }
}
