package net.nuclearlighters.ventriloquist.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * Playback capture on a held [MediaProjection]: one `AudioRecord` (usages MEDIA, GAME and
 * UNKNOWN; 48 kHz, 16-bit, stereo; buffer twice the platform minimum) read on its own thread
 * and handed to every registered [Sink]. The record exists only while at least one sink is
 * registered, so the box captures nothing outside house mode and capture tests.
 */
class CaptureController(private val tag: String) {

    fun interface Sink {
        /** Called on the capture thread with [length] bytes of interleaved PCM. */
        fun onPcm(pcm: ByteArray, length: Int)
    }

    @Volatile
    var projection: MediaProjection? = null

    private val sinks = CopyOnWriteArrayList<Sink>()
    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var stopping = false

    private val window = LevelMeter(CHANNELS)

    @Volatile
    var lastLevels: LevelMeter.Levels? = null
        private set

    @Volatile
    var framesCaptured: Long = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    val running: Boolean get() = synchronized(lock) { record != null }

    /** Registers [sink] and starts capture if it is the first; throws when capture cannot open. */
    fun addSink(sink: Sink) {
        synchronized(lock) {
            if (record == null) open()
            sinks.addIfAbsent(sink)
        }
    }

    /** Unregisters [sink]; capture stops with the last one. */
    fun removeSink(sink: Sink) {
        synchronized(lock) {
            sinks.remove(sink)
            if (sinks.isEmpty()) close()
        }
    }

    /** Stops capture and drops every sink (projection lost, service stopping). */
    fun stopAll() {
        synchronized(lock) {
            sinks.clear()
            close()
        }
    }

    /** Captures for [seconds] and returns the levels. Throws when capture cannot open. */
    fun captureTest(seconds: Int): LevelMeter.Levels {
        val meter = LevelMeter(CHANNELS)
        val sink = Sink { pcm, length -> meter.add(pcm, 0, length) }
        addSink(sink)
        try {
            Thread.sleep(seconds * 1000L)
        } finally {
            removeSink(sink)
        }
        return meter.result()
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller's error path
    private fun open() {
        val p = projection ?: throw IllegalStateException("no media projection: consent needed")
        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IllegalStateException("AudioRecord.getMinBufferSize failed: $minBuffer")
        val bufferBytes = 2 * minBuffer
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: SecurityException) {
            throw IllegalStateException("RECORD_AUDIO not granted: ${e.message}", e)
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException("playback capture unsupported: ${e.message}", e)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord did not initialise")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IllegalStateException("AudioRecord did not start recording")
        }
        // Read in 20 ms chunks (whole frames), never more than half the record buffer.
        val chunk = min(CHUNK_FRAMES * FRAME_BYTES, max(FRAME_BYTES, bufferBytes / 2 / FRAME_BYTES * FRAME_BYTES))
        stopping = false
        record = rec
        lastError = null
        window.reset()
        val t = Thread({ loop(rec, chunk) }, "ventriloquist-capture")
        thread = t
        t.start()
        Log.i(tag, "capture started: buffer $bufferBytes bytes, chunk $chunk bytes")
    }

    private fun close() {
        val rec = record ?: return
        stopping = true
        try {
            rec.stop()
        } catch (e: IllegalStateException) {
            Log.w(tag, "AudioRecord.stop: ${e.message}")
        }
        thread?.join(2000)
        rec.release()
        record = null
        thread = null
        Log.i(tag, "capture stopped")
    }

    private fun loop(rec: AudioRecord, chunk: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(chunk)
        var windowFrames = 0L
        while (!stopping) {
            val n = rec.read(buffer, 0, buffer.size)
            if (n < 0) {
                if (!stopping) {
                    lastError = "AudioRecord.read returned $n"
                    Log.e(tag, lastError!!)
                }
                break
            }
            if (n == 0) continue
            framesCaptured += n / FRAME_BYTES
            window.add(buffer, 0, n)
            windowFrames += n / FRAME_BYTES
            if (windowFrames >= SAMPLE_RATE) {
                lastLevels = window.result()
                window.reset()
                windowFrames = 0
            }
            for (sink in sinks) {
                try {
                    sink.onPcm(buffer, n)
                } catch (e: Exception) {
                    lastError = "sink failed: ${e.message}"
                    Log.e(tag, "capture sink failed", e)
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val FRAME_BYTES = 4
        const val CHUNK_FRAMES = 960
    }
}
