package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.config.Config

/** Monotonic milliseconds for durations, local wall-clock minute of the day for quiet hours. */
interface HouseClock {
    fun nowMs(): Long
    fun minuteOfDay(): Int
}

enum class Activity { PLAYING, PAUSED, IDLE }

data class Observation(
    val activity: Activity,
    val packageName: String? = null,
)

enum class Action { START, STOP }

/** The switches, durations and quiet hours the machine needs, taken from [Config]. */
data class Rules(
    val enabled: Boolean,
    val debounceMs: Long,
    val stopDelayMs: Long,
    val quietStartMinute: Int,
    val quietEndMinute: Int,
) {
    companion object {
        fun of(c: Config) = Rules(
            c.enabled, c.startDebounceS * 1000L, c.stopDelayS * 1000L, c.quietStartMinute, c.quietEndMinute,
        )
    }
}

/**
 * Decides when house mode starts and stops. Pure: inputs are fed in, [evaluate] returns the
 * next action and assumes it succeeds; [startFailed] reports otherwise.
 *
 * Automatic (when [Rules.enabled]): start once anything has been playing for the debounce
 * time, with the screen on, outside quiet hours; stay through a pause; stop when playback has
 * ended, or the screen has been off, for the stop delay, and at once when quiet hours begin.
 * Disabled: stop at once, start nothing. A manual start ([startNow]) runs whether anything
 * plays or not, until [stopNow], until the screen has been off for the stop delay, or until
 * quiet hours begin; it is refused inside quiet hours. After a failed start the machine waits
 * [RETRY_MS] before trying again, or until playback ends and starts anew.
 */
class HouseStateMachine(private val clock: HouseClock) {

    var running = false
        private set

    /** True while a manual start holds house mode on. */
    var manual = false
        private set

    var observation = Observation(Activity.IDLE)
        private set

    var screenOn = true
        private set

    /** Why a wanted start is not happening, for the status page; null when nothing waits. */
    var blocked: String? = null
        private set

    private var playingSince: Long? = null
    private var idleSince: Long? = null
    private var screenOffSince: Long? = null
    private var retryAt = 0L

    fun observe(o: Observation) {
        val now = clock.nowMs()
        if (o.activity == Activity.PLAYING) {
            if (observation.activity != Activity.PLAYING || playingSince == null) playingSince = now
        } else {
            playingSince = null
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

    fun inQuietHours(r: Rules): Boolean {
        val m = clock.minuteOfDay()
        val s = r.quietStartMinute
        val e = r.quietEndMinute
        return when {
            s == e -> false
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    /** A manual start; returns false (and changes nothing) inside quiet hours. */
    fun startNow(r: Rules): Boolean {
        if (inQuietHours(r)) return false
        manual = true
        retryAt = 0
        restartDelays()
        return true
    }

    /** Ends a manual start; automatic mode decides again from the next evaluation. */
    fun stopNow() {
        manual = false
        restartDelays()
    }

    /**
     * The next action. [blockedFor] names a reason house mode cannot start right now (not
     * configured, no capture consent or access); such a start waits, reported in [blocked], with
     * no attempt and no back-off, and goes ahead as soon as the reason is gone.
     */
    fun evaluate(r: Rules, blockedFor: () -> String? = { null }): Action? {
        val now = clock.nowMs()
        val quiet = inQuietHours(r)
        blocked = null
        val off = screenOffSince
        val screenDue = !screenOn && off != null && now - off >= r.stopDelayMs
        if (manual && (quiet || screenDue)) manual = false
        if (running) {
            if (manual) return null
            if (quiet || !r.enabled || screenDue) return stop()
            val idle = idleSince
            val idleDue = observation.activity == Activity.IDLE && idle != null && now - idle >= r.stopDelayMs
            return if (idleDue) stop() else null
        }
        if (!manual) {
            if (!r.enabled) return null
            val since = playingSince
            if (observation.activity != Activity.PLAYING || since == null) return null
            if (now - since < r.debounceMs) return null
            if (!screenOn) return block("screen off")
        }
        if (quiet) return block("quiet hours")
        if (now < retryAt) return block("waiting after a failed start")
        blockedFor()?.let { return block(it) }
        running = true
        return Action.START
    }

    /** The last start failed; everything was put back. */
    fun startFailed() {
        running = false
        manual = false
        retryAt = clock.nowMs() + RETRY_MS
    }

    /** House mode ended for an outside reason (consent lost, a restore request). */
    fun stopped() {
        running = false
        manual = false
    }

    // Stop delays count from the operator's change, not from long before it.
    private fun restartDelays() {
        val now = clock.nowMs()
        if (idleSince != null) idleSince = now
        if (screenOffSince != null) screenOffSince = now
    }

    private fun stop(): Action {
        running = false
        return Action.STOP
    }

    private fun block(reason: String): Action? {
        blocked = reason
        return null
    }

    companion object {
        const val RETRY_MS = 5 * 60 * 1000L
    }
}

/** Turns what the platform reports about playback into an [Observation]. Any app counts. */
object PlaybackClassifier {

    enum class SessionState { PLAYING, PAUSED, OTHER }

    data class Session(val packageName: String, val state: SessionState)

    /** One audio player: whether its usage is MEDIA or GAME, and whether it is known to be
     *  started (null when the platform does not say). */
    data class Player(val media: Boolean, val started: Boolean?)

    fun observe(sessions: List<Session>, players: List<Player>): Observation {
        val playing = sessions.firstOrNull { it.state == SessionState.PLAYING }
        val paused = sessions.firstOrNull { it.state == SessionState.PAUSED }
        val media = players.filter { it.media }
        val activity = when {
            playing != null -> Activity.PLAYING
            media.any { it.started == true } -> Activity.PLAYING
            paused != null && media.isNotEmpty() -> Activity.PAUSED
            else -> Activity.IDLE
        }
        return Observation(activity, (playing ?: paused)?.packageName)
    }
}
