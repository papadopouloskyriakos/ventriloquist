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

    private val r = Rules(enabled = true, debounceMs = 3_000, stopDelayMs = 180_000, quietStartMinute = 23 * 60, quietEndMinute = 8 * 60)
    private val off = r.copy(enabled = false)
    private val playing = Observation(Activity.PLAYING, "com.example.video")
    private val paused = Observation(Activity.PAUSED, "com.example.video")
    private val idle = Observation(Activity.IDLE)

    private fun running(clock: Clock): HouseStateMachine {
        val m = HouseStateMachine(clock)
        m.observe(playing)
        clock.now += 3_000
        assertEquals(Action.START, m.evaluate(r))
        return m
    }

    @Test
    fun startsOnlyAfterTheDebounce() {
        val clock = Clock()
        val m = HouseStateMachine(clock)
        m.observe(playing)
        assertNull(m.evaluate(r))
        clock.now = 2_999
        assertNull(m.evaluate(r))
        clock.now = 3_000
        assertEquals(Action.START, m.evaluate(r))
        assertTrue(m.running)
        assertNull(m.evaluate(r))
    }

    @Test
    fun aShortBlipDoesNotStart() {
        val clock = Clock()
        val m = HouseStateMachine(clock)
        m.observe(playing)
        clock.now = 2_000
        m.observe(idle)
        m.observe(playing)
        clock.now = 4_000
        assertNull(m.evaluate(r)) // the debounce restarted at 2 s
        clock.now = 5_000
        assertEquals(Action.START, m.evaluate(r))
    }

    @Test
    fun aPauseKeepsItAndPlaybackEndedStopsAfterTheDelay() {
        val clock = Clock()
        val m = running(clock)
        m.observe(paused)
        clock.now += 600_000
        assertNull(m.evaluate(r)) // paused for ten minutes: still on
        m.observe(idle)
        clock.now += 179_999
        assertNull(m.evaluate(r))
        clock.now += 1
        assertEquals(Action.STOP, m.evaluate(r))
        assertFalse(m.running)
    }

    @Test
    fun playbackComingBackWithinTheDelayKeepsIt() {
        val clock = Clock()
        val m = running(clock)
        m.observe(idle)
        clock.now += 120_000
        m.observe(playing)
        clock.now += 120_000
        assertNull(m.evaluate(r))
        assertTrue(m.running)
    }

    @Test
    fun screenOffStopsAfterTheDelayAndBlocksAStart() {
        val clock = Clock()
        val m = running(clock)
        m.screen(false)
        clock.now += 179_999
        assertNull(m.evaluate(r))
        clock.now += 1
        assertEquals(Action.STOP, m.evaluate(r))

        val n = HouseStateMachine(Clock())
        n.screen(false)
        n.observe(playing)
        assertNull(n.evaluate(r.copy(debounceMs = 0)))
        assertEquals("screen off", n.blocked)
    }

    @Test
    fun nothingStartsInQuietHours() {
        val clock = Clock(minute = 23 * 60 + 5)
        val m = HouseStateMachine(clock)
        m.observe(playing)
        clock.now = 10_000
        assertNull(m.evaluate(r))
        assertEquals("quiet hours", m.blocked)
        clock.minute = 7 * 60 + 59
        assertNull(m.evaluate(r))
        clock.minute = 8 * 60
        assertEquals(Action.START, m.evaluate(r))
    }

    @Test
    fun quietHoursStopARunningSessionAtOnce() {
        val clock = Clock(minute = 22 * 60 + 59)
        val m = running(clock)
        assertNull(m.evaluate(r))
        clock.minute = 23 * 60
        assertEquals(Action.STOP, m.evaluate(r))
        assertNull(m.evaluate(r))
    }

    @Test
    fun quietHoursMayNotCrossMidnightOrBeOff() {
        val clock = Clock(minute = 13 * 60)
        val m = HouseStateMachine(clock)
        assertTrue(m.inQuietHours(r.copy(quietStartMinute = 12 * 60, quietEndMinute = 14 * 60)))
        assertFalse(m.inQuietHours(r.copy(quietStartMinute = 14 * 60, quietEndMinute = 16 * 60)))
        assertFalse(m.inQuietHours(r.copy(quietStartMinute = 0, quietEndMinute = 0)))
        clock.minute = 2 * 60
        assertTrue(m.inQuietHours(r))
    }

    @Test
    fun disabledStopsAtOnceAndStartsNothing() {
        val clock = Clock()
        val m = running(clock)
        assertEquals(Action.STOP, m.evaluate(off))
        clock.now += 60_000
        assertNull(m.evaluate(off))
        assertNull(m.blocked)
        assertEquals(Action.START, m.evaluate(r)) // enabled again while it plays
    }

    @Test
    fun aManualStartRunsWithoutPlaybackUntilStopped() {
        val clock = Clock()
        val m = HouseStateMachine(clock)
        assertTrue(m.startNow(off))
        assertEquals(Action.START, m.evaluate(off))
        clock.now += 3_600_000
        assertNull(m.evaluate(off)) // nothing plays, still held on by hand
        m.stopNow()
        assertEquals(Action.STOP, m.evaluate(off))
    }

    @Test
    fun aManualStartIsRefusedInQuietHoursAndEndsWhenTheyBegin() {
        val clock = Clock(minute = 23 * 60 + 30)
        val m = HouseStateMachine(clock)
        assertFalse(m.startNow(r))
        assertFalse(m.manual)
        clock.minute = 22 * 60 + 50
        assertTrue(m.startNow(r))
        assertEquals(Action.START, m.evaluate(r))
        clock.minute = 23 * 60
        assertEquals(Action.STOP, m.evaluate(r))
        assertFalse(m.manual)
    }

    @Test
    fun aManualStartEndsWhenTheScreenHasBeenOff() {
        val clock = Clock()
        val m = HouseStateMachine(clock)
        m.startNow(r)
        assertEquals(Action.START, m.evaluate(r))
        m.screen(false)
        clock.now += 180_000
        assertEquals(Action.STOP, m.evaluate(r))
        assertFalse(m.manual)
    }

    @Test
    fun aFailedStartWaitsUnlessPlaybackStartsAnew() {
        val clock = Clock()
        val m = running(clock)
        m.startFailed()
        clock.now += 1_000
        assertNull(m.evaluate(r))
        assertEquals("waiting after a failed start", m.blocked)
        clock.now += HouseStateMachine.RETRY_MS
        assertEquals(Action.START, m.evaluate(r))
        m.startFailed()
        m.observe(idle)
        m.observe(playing)
        clock.now += 3_000
        assertEquals(Action.START, m.evaluate(r))
    }

    @Test
    fun aBlockedStartWaitsWithoutBackOff() {
        val clock = Clock()
        val m = HouseStateMachine(clock)
        m.observe(playing)
        clock.now = 3_000
        assertNull(m.evaluate(r) { "capture consent needed" })
        assertEquals("capture consent needed", m.blocked)
        assertFalse(m.running)
        assertEquals(Action.START, m.evaluate(r) { null })
    }

    @Test
    fun anOutsideStopEndsItQuietly() {
        val clock = Clock()
        val m = running(clock)
        m.stopped()
        assertFalse(m.running)
        m.observe(idle)
        clock.now += 300_000
        assertNull(m.evaluate(r))
    }
}
