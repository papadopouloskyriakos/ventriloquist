package net.nuclearlighters.ventriloquist

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import net.nuclearlighters.ventriloquist.capture.CaptureController
import net.nuclearlighters.ventriloquist.config.ConfigStore
import net.nuclearlighters.ventriloquist.control.ControlApi
import net.nuclearlighters.ventriloquist.control.ControlBackend
import net.nuclearlighters.ventriloquist.http.HttpServer
import net.nuclearlighters.ventriloquist.shield.AndroidAudioControls
import net.nuclearlighters.ventriloquist.shield.LatencyModule
import net.nuclearlighters.ventriloquist.store.Ledger
import net.nuclearlighters.ventriloquist.store.PrefsStore
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The long-running part of Ventriloquist. Runs in the foreground (type mediaProjection), holds
 * the playback-capture consent for as long as it lives, serves the control endpoint, and owns
 * every change made to the box so that it can put them back.
 *
 * Shield-side changes run on one worker thread, so the endpoint and (later) the automatic
 * watcher never interleave.
 */
class VentriloquistService : Service(), ControlBackend {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ventriloquist-house")
    }
    private lateinit var audio: AndroidAudioControls
    private lateinit var latency: LatencyModule
    private val capture = CaptureController(TAG)
    private var server: HttpServer? = null

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var consent: String = CONSENT_NEEDED

    @Volatile
    private var lastError: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = AndroidAudioControls(this)
        latency = LatencyModule(audio, Ledger(PrefsStore.open(this, LEDGER_FILE), "shield"))
        startForegroundCompat()
        if (latency.dirty) {
            // A previous run did not finish its restore (crash, kill, power loss).
            val leftovers = latency.originals()
            val errors = latency.restore()
            Log.w(TAG, "restored after an unclean stop: $leftovers; errors: $errors")
            if (errors.isNotEmpty()) lastError = "restore at start: ${errors.joinToString("; ")}"
        }
        val http = HttpServer(PORT, ControlApi(this)::handle) { Log.i(TAG, it) }
        try {
            http.start()
            server = http
        } catch (e: IOException) {
            lastError = "control endpoint did not start: ${e.message}"
            Log.e(TAG, lastError!!)
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROJECTION -> acceptProjection(intent)
            ACTION_CONSENT_DENIED -> {
                consent = CONSENT_DENIED
                Log.w(TAG, "capture consent denied")
            }
            ACTION_BOOT -> if (projection == null) requestConsent()
            ACTION_CONFIG_CHANGED -> Log.i(TAG, "configuration changed")
            else -> Log.i(TAG, "start (idle)")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        instance = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun shutdown() {
        capture.stopAll()
        val errors = runOnWorker { latency.restore() }
        if (errors.isNotEmpty()) Log.e(TAG, "restore at stop failed: $errors")
        server?.stop()
        server = null
        projection?.stop()
        projection = null
    }

    // --- consent and projection -------------------------------------------------------------

    private fun requestConsent() {
        try {
            startActivity(
                Intent(this, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: RuntimeException) {
            lastError = "could not open the consent screen: ${e.message}"
            Log.e(TAG, lastError!!)
        }
    }

    private fun acceptProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = parcelableIntent(intent, EXTRA_DATA)
        if (data == null) {
            lastError = "projection result without data"
            return
        }
        // Android 14 and later want the mediaProjection type declared after consent and before
        // getMediaProjection; on earlier versions the service already runs with it.
        holdsProjection = true
        startForegroundCompat()
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: RuntimeException) {
            lastError = "getMediaProjection failed: ${e.message}"
            Log.e(TAG, lastError!!, e)
            null
        }
        if (p == null) {
            holdsProjection = false
            consent = CONSENT_NEEDED
            return
        }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "media projection stopped")
                if (projection === p) onProjectionLost()
            }
        }, main)
        projection?.stop()
        projection = p
        capture.projection = p
        consent = CONSENT_READY
        Log.i(TAG, "media projection held")
        updateNotification()
    }

    private fun onProjectionLost() {
        projection = null
        capture.projection = null
        capture.stopAll()
        consent = CONSENT_NEEDED
        updateNotification()
    }

    @Suppress("DEPRECATION")
    private fun parcelableIntent(intent: Intent, name: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(name, Intent::class.java)
        } else {
            intent.getParcelableExtra(name)
        }

    // --- foreground notification ------------------------------------------------------------

    @Volatile
    private var holdsProjection = false

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (holdsProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        startForeground(NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val text = getString(
            if (projection != null) R.string.notification_ready else R.string.notification_idle,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    // --- control backend --------------------------------------------------------------------

    override fun controlToken(): String = ConfigStore.get(this).controlToken

    override fun status(): Map<String, Any?> {
        val reading = latency.readLatency()
        val config = ConfigStore.get(this)
        return linkedMapOf(
            "app" to "ventriloquist",
            "version" to versionName(),
            "projection_ready" to (projection != null),
            "consent" to consent,
            "permissions" to linkedMapOf(
                "record_audio" to granted(Manifest.permission.RECORD_AUDIO),
                "write_secure_settings" to granted(Manifest.permission.WRITE_SECURE_SETTINGS),
            ),
            "hdmi_latency_ms" to reading.ms,
            "hdmi_latency_raw" to reading.raw,
            "hdmi_volume" to audio.musicVolume(),
            "hdmi_volume_max" to audio.musicVolumeMax(),
            "volume_fixed" to audio.volumeFixed(),
            "surround_setting" to audio.surroundSetting(),
            "tv_muted" to latency.tvMuted,
            "dirty" to latency.dirty,
            "originals" to latency.originals(),
            "mode" to "idle",
            "capture" to linkedMapOf(
                "running" to capture.running,
                "frames" to capture.framesCaptured,
                "levels" to capture.lastLevels?.toMap(),
                "error" to capture.lastError,
            ),
            "last_error" to lastError,
            "config" to config.toMap(redactSecrets = true),
        )
    }

    override fun readLatency(): Map<String, Any?> = runOnWorker {
        val r = latency.readLatency()
        linkedMapOf("hdmi_latency_ms" to r.ms, "raw" to r.raw)
    }

    override fun setLatency(ms: Int): Map<String, Any?> = runOnWorker {
        val r = latency.setLatency(ms)
        linkedMapOf(
            "requested_ms" to ms,
            "read_back_ms" to r.ms,
            "raw" to r.raw,
            "accepted" to (r.ms == ms),
            "original_ms" to latency.originals()[LatencyModule.K_LATENCY],
        )
    }

    override fun setTvMute(on: Boolean): Map<String, Any?> = runOnWorker {
        val volume = latency.setTvMuted(on)
        linkedMapOf(
            "on" to on,
            "hdmi_volume" to volume,
            "hdmi_volume_max" to audio.musicVolumeMax(),
            "original_volume" to latency.originals()[LatencyModule.K_VOLUME],
        )
    }

    override fun captureTest(seconds: Int): Map<String, Any?> {
        if (projection == null) throw IllegalStateException("consent needed: no media projection held")
        val levels = try {
            capture.captureTest(seconds)
        } catch (e: IllegalStateException) {
            lastError = "capture test: ${e.message}"
            throw e
        }
        return linkedMapOf(
            "seconds" to seconds,
            "levels" to levels.toMap(),
            "hdmi_volume" to audio.musicVolume(),
            "tv_muted" to latency.tvMuted,
        )
    }

    override fun restore(): Map<String, Any?> = runOnWorker {
        val before = latency.originals()
        val errors = latency.restore()
        if (errors.isNotEmpty()) lastError = "restore: ${errors.joinToString("; ")}"
        linkedMapOf("restored" to before, "errors" to errors, "dirty" to latency.dirty)
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    /** Runs [block] on the worker thread and waits; rethrows its exception unchanged. */
    private fun <T> runOnWorker(block: () -> T): T {
        val future = worker.submit(Callable { block() })
        try {
            return future.get(WORKER_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    companion object {
        const val TAG = "Ventriloquist"
        const val PORT = 8770
        const val LEDGER_FILE = "ledger"
        const val WORKER_TIMEOUT_S = 60L

        const val ACTION_START = "net.nuclearlighters.ventriloquist.action.START"
        const val ACTION_STOP = "net.nuclearlighters.ventriloquist.action.STOP"
        const val ACTION_BOOT = "net.nuclearlighters.ventriloquist.action.BOOT"
        const val ACTION_PROJECTION = "net.nuclearlighters.ventriloquist.action.PROJECTION"
        const val ACTION_CONSENT_DENIED = "net.nuclearlighters.ventriloquist.action.CONSENT_DENIED"
        const val ACTION_CONFIG_CHANGED = "net.nuclearlighters.ventriloquist.action.CONFIG_CHANGED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        const val CONSENT_NEEDED = "needed"
        const val CONSENT_READY = "ready"
        const val CONSENT_DENIED = "denied"

        const val CHANNEL_ID = "ventriloquist"
        const val NOTIFICATION_ID = 1

        @Volatile
        var instance: VentriloquistService? = null
            private set
    }
}
