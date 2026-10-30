package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.house.PlaybackClassifier.Player
import net.nuclearlighters.ventriloquist.house.PlaybackClassifier.Session
import net.nuclearlighters.ventriloquist.house.PlaybackClassifier.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClassifierTest {

    @Test
    fun aPlayingSessionOfAnyAppIsPlaying() {
        val o = PlaybackClassifier.observe(
            listOf(Session("com.example.video", SessionState.PLAYING)),
            listOf(Player(media = true, started = true)),
        )
        assertEquals(Observation(Activity.PLAYING, "com.example.video"), o)
    }

    @Test
    fun aStartedMediaPlayerWithoutASessionIsPlaying() {
        val o = PlaybackClassifier.observe(emptyList(), listOf(Player(media = true, started = true)))
        assertEquals(Observation(Activity.PLAYING, null), o)
    }

    @Test
    fun aPausedSessionWithAMediaPlayerIsPaused() {
        val o = PlaybackClassifier.observe(
            listOf(Session("com.example.music", SessionState.PAUSED)),
            listOf(Player(media = true, started = false)),
        )
        assertEquals(Observation(Activity.PAUSED, "com.example.music"), o)
    }

    @Test
    fun systemSoundsAndLeftoverSessionsAreIdle() {
        assertEquals(
            Activity.IDLE,
            PlaybackClassifier.observe(emptyList(), listOf(Player(media = false, started = true))).activity,
        )
        // a paused session whose player is gone is not playback any more
        assertEquals(
            Activity.IDLE,
            PlaybackClassifier.observe(listOf(Session("com.example.video", SessionState.PAUSED)), emptyList()).activity,
        )
        assertEquals(Activity.IDLE, PlaybackClassifier.observe(emptyList(), emptyList()).activity)
    }

    @Test
    fun theSessionStateWinsWhenThePlayerStateIsUnknown() {
        val o = PlaybackClassifier.observe(
            listOf(Session("com.example.video", SessionState.PLAYING)),
            listOf(Player(media = true, started = null)),
        )
        assertEquals(Activity.PLAYING, o.activity)
    }
}
