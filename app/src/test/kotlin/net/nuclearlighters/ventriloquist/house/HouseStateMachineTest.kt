package net.nuclearlighters.ventriloquist.house

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseStateMachineTest {

    private class Clock(var now: Long = 0, var minute: Int = 12 * 60) : HouseClock {
        override fun nowMs() = now
        override fun minuteOfDay() = minute
    }

    private val t = Timing(debounceMs = 3_000, stopDelayMs = 180_000, quietStartMinute = 23 * 60, quietEndMinute = 8 * 60)
    private val film = Observation(Activity.PLAYING, HouseMode.FILM, "com.example.video")
    private val music = Observation(Activity.PLAYING, HouseMode.MUSIC, "com.example.music")
    private val paused = Observation(Activity.PAUSED, HouseMode.FILM, "com.example.video")
    private val idle = Observation(Activity.IDLE, HouseMode.FILM)

    private fun machine(clock: Clock) = HouseStateMachine(clock)

    private fun runningFilm(clock: Clock): HouseStateMachine {
        val m = machine(clock)
        m.observe(film)
        clock.now += 3_000
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))
        return m
    }

    @Test
    fun startsOnlyAfterTheDebounce() {
        val clock = Clock()
        val m = machine(clock)
        m.observe(film)
        assertNull(m.evaluate(t))
        clock.now = 2_999
        assertNull(m.evaluate(t))
        clock.now = 3_000
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))
        assertEquals(HouseMode.FILM, m.running)
        assertNull(m.evaluate(t))
    }

    @Test
    fun aShortBlipDoesNotStart() {
        val clock = Clock()
        val m = machine(clock)
        m.observe(film)
        clock.now = 2_000
        m.observe(idle)
        m.observe(film)
        clock.now = 4_000
        assertNull(m.evaluate(t)) // the debounce restarted at 2 s
        clock.now = 5_000
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))
    }

    @Test
    fun staysThroughAPause() {
        val clock = Clock()
        val m = runningFilm(clock)
        m.observe(paused)
        clock.now += 60 * 60 * 1000L
        m.observe(paused)
        assertNull(m.evaluate(t))
        assertEquals(HouseMode.FILM, m.running)
    }

    @Test
    fun stopsWhenPlaybackHasEndedForTheStopDelay() {
        val clock = Clock()
        val m = runningFilm(clock)
        m.observe(idle)
        val ended = clock.now
        clock.now = ended + 179_999
        m.observe(idle)
        assertNull(m.evaluate(t))
        clock.now = ended + 180_000
        assertEquals(Action.Stop, m.evaluate(t))
        assertNull(m.running)
    }

    @Test
    fun playbackResumedWithinTheDelayKeepsRunning() {
        val clock = Clock()
        val m = runningFilm(clock)
        m.observe(idle)
        clock.now += 100_000
        m.observe(film)
        clock.now += 200_000
        assertNull(m.evaluate(t))
        assertEquals(HouseMode.FILM, m.running)
    }

    @Test
    fun screenOffStopsAfterTheDelayAndBlocksStarts() {
        val clock = Clock()
        val m = runningFilm(clock)
        m.screen(false)
        val off = clock.now
        clock.now = off + 179_000
        assertNull(m.evaluate(t))
        clock.now = off + 180_000
        assertEquals(Action.Stop, m.evaluate(t))

        clock.now += 10_000
        m.observe(film)
        clock.now += 5_000
        assertNull(m.evaluate(t))
        assertEquals("screen off", m.blocked)
        m.screen(true)
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))
    }

    @Test
    fun neverStartsInQuietHoursButLetsARunningModeFinish() {
        val clock = Clock(minute = 23 * 60 + 5)
        val m = machine(clock)
        m.observe(film)
        clock.now = 10_000
        assertNull(m.evaluate(t))
        assertEquals("quiet hours", m.blocked)

        val clock2 = Clock(minute = 22 * 60 + 59)
        val m2 = runningFilm(clock2)
        clock2.minute = 23 * 60 + 30
        m2.observe(film)
        assertNull(m2.evaluate(t))
        assertEquals(HouseMode.FILM, m2.running)
        m2.observe(idle)
        clock2.now += 180_000
        assertEquals(Action.Stop, m2.evaluate(t))
        m2.observe(film)
        clock2.now += 10_000
        assertNull(m2.evaluate(t))
    }

    @Test
    fun quietHoursWrapAroundMidnight() {
        val clock = Clock()
        val m = machine(clock)
        val cases = mapOf(22 * 60 + 59 to false, 23 * 60 to true, 0 to true, 7 * 60 + 59 to true, 8 * 60 to false, 12 * 60 to false)
        for ((minute, quiet) in cases) {
            clock.minute = minute
            assertEquals("minute $minute", quiet, m.inQuietHours(t))
        }
        val daytime = Timing(3_000, 180_000, 9 * 60, 17 * 60)
        clock.minute = 12 * 60
        assertTrue(m.inQuietHours(daytime))
        clock.minute = 17 * 60
        assertFalse(m.inQuietHours(daytime))
        val none = Timing(3_000, 180_000, 8 * 60, 8 * 60)
        assertFalse(m.inQuietHours(none))
    }

    @Test
    fun switchesBetweenFilmAndMusicAfterTheDebounce() {
        val clock = Clock()
        val m = runningFilm(clock)
        m.observe(music)
        val changed = clock.now
        clock.now = changed + 2_999
        assertNull(m.evaluate(t))
        clock.now = changed + 3_000
        assertEquals(Action.Switch(HouseMode.MUSIC), m.evaluate(t))
        assertEquals(HouseMode.MUSIC, m.running)
        assertNull(m.evaluate(t))
    }

    @Test
    fun offStopsAtOnceAndAutoResumes() {
        val clock = Clock()
        val m = runningFilm(clock)
        assertTrue(m.setOverride(ModeOverride.OFF, t))
        assertEquals(Action.Stop, m.evaluate(t))
        clock.now += 10_000
        m.observe(film)
        assertNull(m.evaluate(t))
        assertTrue(m.setOverride(ModeOverride.AUTO, t))
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))
    }

    @Test
    fun manualModeRunsWithoutPlaybackAndIsRefusedInQuietHours() {
        val clock = Clock()
        val m = machine(clock)
        assertTrue(m.setOverride(ModeOverride.MUSIC, t))
        assertEquals(Action.Start(HouseMode.MUSIC), m.evaluate(t))
        m.observe(idle)
        clock.now += 60 * 60 * 1000L
        assertNull(m.evaluate(t)) // a manual mode ignores the playback stop rule
        assertTrue(m.setOverride(ModeOverride.FILM, t))
        assertEquals(Action.Switch(HouseMode.FILM), m.evaluate(t))

        val night = Clock(minute = 2 * 60)
        val n = machine(night)
        assertFalse(n.setOverride(ModeOverride.FILM, t))
        assertEquals(ModeOverride.AUTO, n.override)
        assertTrue(n.setOverride(ModeOverride.OFF, t))
    }

    @Test
    fun aManualModeEndsWhenTheScreenStaysOff() {
        val clock = Clock()
        val m = machine(clock)
        m.setOverride(ModeOverride.FILM, t)
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))
        m.screen(false)
        clock.now += 180_000
        assertEquals(Action.Stop, m.evaluate(t))
        assertEquals(ModeOverride.AUTO, m.override)
    }

    @Test
    fun handOverToAutoRestartsTheStopDelay() {
        val clock = Clock()
        val m = machine(clock)
        m.observe(idle)
        m.setOverride(ModeOverride.FILM, t)
        m.evaluate(t)
        clock.now += 60 * 60 * 1000L
        m.observe(idle)
        m.setOverride(ModeOverride.AUTO, t)
        clock.now += 179_000
        assertNull(m.evaluate(t))
        clock.now += 1_000
        assertEquals(Action.Stop, m.evaluate(t))
    }

    @Test
    fun aFailedStartWaitsBeforeTryingAgain() {
        val clock = Clock()
        val m = runningFilm(clock)
        m.startFailed()
        assertNull(m.running)
        clock.now += 60_000
        m.observe(film)
        assertNull(m.evaluate(t))
        assertEquals("waiting after a failed start", m.blocked)
        clock.now += HouseStateMachine.RETRY_MS
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))

        m.startFailed()
        m.observe(idle) // playback ended: the next playback gets a fresh attempt
        m.observe(film)
        clock.now += 3_000
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))
    }

    @Test
    fun aFailedManualStartFallsBackToAuto() {
        val clock = Clock()
        val m = machine(clock)
        m.setOverride(ModeOverride.FILM, t)
        assertEquals(Action.Start(HouseMode.FILM), m.evaluate(t))
        m.startFailed()
        assertEquals(ModeOverride.AUTO, m.override)
        assertNull(m.evaluate(t))
    }
}
