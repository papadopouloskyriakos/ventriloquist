package net.nuclearlighters.ventriloquist.watch

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import net.nuclearlighters.ventriloquist.NotificationListener
import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.house.Observation
import net.nuclearlighters.ventriloquist.house.PlaybackClassifier

/**
 * The platform inputs of the automatic mode: active media sessions (through the notification
 * listener grant), active audio players (usage and content type) and screen on or off.
 * [snapshot] polls; [onChange] is called on the given handler when something changed, so the
 * caller can evaluate at once instead of at its next tick.
 */
class PlaybackWatcher(private val context: Context, private val onChange: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val listener = ComponentName(context, NotificationListener::class.java)

    @Volatile
    var listenerGranted = false
        private set

    @Volatile
    var screenOn = true
        private set

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onChange()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
            onChange()
        }
    }

    fun start(handler: Handler) {
        screenOn = power.isInteractive
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(screenReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenReceiver, filter, null, handler)
        }
    }

    fun stop() {
        audio.unregisterAudioPlaybackCallback(playbackCallback)
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // was not registered
        }
    }

    fun snapshot(config: Config): Observation {
        val active = try {
            sessions.getActiveSessions(listener).also { listenerGranted = true }
        } catch (e: SecurityException) {
            listenerGranted = false
            emptyList()
        }
        val sessionList = active.map {
            PlaybackClassifier.Session(it.packageName, sessionState(it.playbackState?.state))
        }
        val players = audio.activePlaybackConfigurations.map { c ->
            val attributes = c.audioAttributes
            PlaybackClassifier.Player(
                media = attributes.usage == AudioAttributes.USAGE_MEDIA ||
                    attributes.usage == AudioAttributes.USAGE_GAME,
                content = content(attributes.contentType),
                started = started(c),
            )
        }
        return PlaybackClassifier.observe(sessionList, players, config)
    }

    companion object {
        fun sessionState(state: Int?): PlaybackClassifier.SessionState = when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
            -> PlaybackClassifier.SessionState.PLAYING
            PlaybackState.STATE_PAUSED -> PlaybackClassifier.SessionState.PAUSED
            else -> PlaybackClassifier.SessionState.OTHER
        }

        fun content(type: Int): PlaybackClassifier.Content = when (type) {
            AudioAttributes.CONTENT_TYPE_MOVIE -> PlaybackClassifier.Content.MOVIE
            AudioAttributes.CONTENT_TYPE_MUSIC -> PlaybackClassifier.Content.MUSIC
            AudioAttributes.CONTENT_TYPE_SPEECH -> PlaybackClassifier.Content.SPEECH
            else -> PlaybackClassifier.Content.UNKNOWN
        }

        /**
         * The player state has no public getter; its log form ("state:started") is the only
         * public trace of it. Null when the form does not say.
         */
        fun started(c: AudioPlaybackConfiguration): Boolean? {
            val text = c.toString()
            val i = text.indexOf("state:")
            if (i < 0) return null
            return text.startsWith("started", i + 6)
        }
    }
}
