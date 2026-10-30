package net.nuclearlighters.ventriloquist.shield

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/** [AudioControls] on the real AudioManager and Settings.Global. */
class AndroidAudioControls(context: Context) : AudioControls {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val resolver = context.contentResolver

    override fun getParameters(keys: String): String = audio.getParameters(keys) ?: ""

    override fun setParameters(keyValuePairs: String) = audio.setParameters(keyValuePairs)

    override fun musicVolume(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun musicVolumeMax(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun setMusicVolume(index: Int) =
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)

    override fun musicMuted(): Boolean = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun setMusicMuted(muted: Boolean) = audio.adjustStreamVolume(
        AudioManager.STREAM_MUSIC,
        if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
        0,
    )

    override fun surroundSetting(): String? =
        Settings.Global.getString(resolver, ShieldModule.SURROUND_KEY)

    override fun setSurroundSetting(value: String?) {
        if (!Settings.Global.putString(resolver, ShieldModule.SURROUND_KEY, value)) {
            throw IllegalStateException("Settings.Global refused ${ShieldModule.SURROUND_KEY}")
        }
    }

    /** True when the platform reports fixed volume (a TV box with CEC volume control may). */
    fun volumeFixed(): Boolean = audio.isVolumeFixed
}
