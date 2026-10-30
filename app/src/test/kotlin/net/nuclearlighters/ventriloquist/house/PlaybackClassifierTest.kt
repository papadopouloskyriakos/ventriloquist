package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.house.PlaybackClassifier.Content
import net.nuclearlighters.ventriloquist.house.PlaybackClassifier.Player
import net.nuclearlighters.ventriloquist.house.PlaybackClassifier.Session
import net.nuclearlighters.ventriloquist.house.PlaybackClassifier.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClassifierTest {

    private val config = Config(
        filmPackages = listOf("com.example.video"),
        musicPackages = listOf("com.example.music"),
    )

    @Test
    fun listedPackagesWinOverContentType() {
        assertEquals(HouseMode.FILM, PlaybackClassifier.classify("com.example.video", Content.MUSIC, config))
        assertEquals(HouseMode.MUSIC, PlaybackClassifier.classify("com.example.music", Content.MOVIE, config))
    }

    @Test
    fun otherwiseMusicContentIsMusicAndEverythingElseFilm() {
        assertEquals(HouseMode.MUSIC, PlaybackClassifier.classify("com.other", Content.MUSIC, config))
        assertEquals(HouseMode.MUSIC, PlaybackClassifier.classify(null, Content.MUSIC, config))
        for (c in listOf(Content.MOVIE, Content.SPEECH, Content.UNKNOWN)) {
            assertEquals(HouseMode.FILM, PlaybackClassifier.classify("com.other", c, config))
        }
    }

    @Test
    fun aPlayingSessionIsPlayback() {
        val o = PlaybackClassifier.observe(
            listOf(Session("com.example.music", SessionState.PLAYING)),
            listOf(Player(true, Content.MUSIC, null)),
            config,
        )
        assertEquals(Observation(Activity.PLAYING, HouseMode.MUSIC, "com.example.music"), o)
    }

    @Test
    fun aStartedMediaPlayerWithoutSessionIsPlayback() {
        val o = PlaybackClassifier.observe(emptyList(), listOf(Player(true, Content.MOVIE, true)), config)
        assertEquals(Activity.PLAYING, o.activity)
        assertEquals(HouseMode.FILM, o.kind)
    }

    @Test
    fun aPausedSessionWithItsPlayerIsAPause() {
        val o = PlaybackClassifier.observe(
            listOf(Session("com.example.video", SessionState.PAUSED)),
            listOf(Player(true, Content.MOVIE, false)),
            config,
        )
        assertEquals(Activity.PAUSED, o.activity)
    }

    @Test
    fun aPausedSessionWithoutAnyPlayerIsIdle() {
        val o = PlaybackClassifier.observe(listOf(Session("com.example.video", SessionState.PAUSED)), emptyList(), config)
        assertEquals(Activity.IDLE, o.activity)
    }

    @Test
    fun nonMediaPlayersAndUnknownStatesAreIdle() {
        val o = PlaybackClassifier.observe(
            listOf(Session("com.example.video", SessionState.OTHER)),
            listOf(Player(false, Content.UNKNOWN, true), Player(true, Content.MOVIE, null)),
            config,
        )
        assertEquals(Activity.IDLE, o.activity)
    }

    @Test
    fun thePlayingSessionIsPreferredOverAPausedOne() {
        val o = PlaybackClassifier.observe(
            listOf(Session("com.example.video", SessionState.PAUSED), Session("com.example.music", SessionState.PLAYING)),
            listOf(Player(true, Content.MUSIC, true)),
            config,
        )
        assertEquals("com.example.music", o.packageName)
        assertEquals(HouseMode.MUSIC, o.kind)
    }
}
