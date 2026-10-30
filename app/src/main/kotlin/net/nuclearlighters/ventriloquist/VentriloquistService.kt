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
import android.os.SystemClock
import android.util.Log
import net.nuclearlighters.ventriloquist.capture.CaptureController
import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.config.ConfigStore
import net.nuclearlighters.ventriloquist.control.ApiException
import net.nuclearlighters.ventriloquist.control.ControlApi
import net.nuclearlighters.ventriloquist.control.ControlBackend
import net.nuclearlighters.ventriloquist.house.Action
import net.nuclearlighters.ventriloquist.house.HouseClock
import net.nuclearlighters.ventriloquist.house.HouseMode
import net.nuclearlighters.ventriloquist.house.HouseSession
import net.nuclearlighters.ventriloquist.house.HouseStateMachine
import net.nuclearlighters.ventriloquist.house.ModeOverride
import net.nuclearlighters.ventriloquist.house.Timing
import net.nuclearlighters.ventriloquist.house.VbanStreamer
import net.nuclearlighters.ventriloquist.http.HttpServer
import net.nuclearlighters.ventriloquist.ma.MaClient
import net.nuclearlighters.ventriloquist.shield.AndroidAudioControls
import net.nuclearlighters.ventriloquist.shield.LatencyModule
import net.nuclearlighters.ventriloquist.store.Ledger
import net.nuclearlighters.ventriloquist.store.PrefsStore
import net.nuclearlighters.ventriloquist.watch.PlaybackWatcher
import java.io.IOException
import java.time.LocalTime
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The long-running part of Ventriloquist. Runs in the foreground (type mediaProjection), holds
 * the playback-capture consent for as long as it lives, serves the control endpoint, runs the
 * automatic house mode, and owns every change made to the box and to Music Assistant so that
 * it can put them back.
 *
 * All house work (watcher ticks, mode changes, Shield and Music Assistant calls) runs on one
 * worker thread, so the endpoint and the watcher never interleave.
 */
class VentriloquistService : Service(), ControlBackend {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ventriloquist-house")
    }
    private lateinit var audio: AndroidAudioControls
    private lateinit var latency: LatencyModule
    private lateinit var house: HouseSession
    private lateinit var watcher: PlaybackWatcher
    private val capture = CaptureController(TAG)
    private val streamer = VbanStreamer(capture)
    private val machine = HouseStateMachine(object : HouseClock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
        override fun minuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
    })
    private var server: HttpServer? = null
    private var lastLeftoverAttempt = 0L

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var consent: String = CONSENT_NEEDED

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var holdsProjection = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = AndroidAudioControls(this)
        val ledgers = PrefsStore.open(this, LEDGER_FILE)
        latency = LatencyModule(audio, Ledger(ledgers, "shield"))
        house = HouseSession(latency, Ledger(ledgers, "music_assistant"), { c -> MaClient(c.maUrl, c.maToken) }, streamer)
        startForegroundCompat()
        if (latency.dirty) {
            // A previous run did not finish its restore (crash, kill, power loss). The Shield
            // side goes back at once; Music Assistant follows on the worker (network).
            val leftovers = latency.originals()
            val errors = latency.restore()
            Log.w(TAG, "restored after an unclean stop: $leftovers; errors: $errors")
            if (errors.isNotEmpty()) lastError = "restore at start: ${errors.joinToString("; ")}"
        }
        if (house.maDirty) worker.execute { cleanUpLeftovers(force = true) }
        val http = HttpServer(PORT, ControlApi(this)::handle) { Log.i(TAG, it) }
        try {
            http.start()
            server = http
        } catch (e: IOException) {
            lastError = "control endpoint did not start: ${e.message}"
            Log.e(TAG, lastError!!)
        }
        watcher = PlaybackWatcher(this) { post { safeTick() } }
        watcher.start(main)
        worker.scheduleWithFixedDelay({ safeTick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
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
            ACTION_CONFIG_CHANGED -> {
                Log.i(TAG, "configuration changed")
                post { safeTick() }
            }
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

    private var shutDown = false

    private fun shutdown() {
        if (shutDown) return
        shutDown = true
        watcher.stop()
        val errors = try {
            runOnWorker {
                machine.setOverride(ModeOverride.OFF, Timing.of(ConfigStore.get(this)))
                machine.stopped()
                house.stop(ConfigStore.get(this))
            }
        } catch (e: Exception) {
            capture.stopAll()
            latency.restore() + "worker: ${e.message}"
        }
        if (errors.isNotEmpty()) Log.e(TAG, "restore at stop: $errors")
        server?.stop()
        server = null
        projection?.stop()
        projection = null
    }

    // --- automatic mode ---------------------------------------------------------------------

    private fun post(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (e: RejectedExecutionException) {
            // shutting down
        }
    }

    private fun safeTick() {
        try {
            tick()
        } catch (e: Exception) {
            lastError = "tick: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "tick failed", e)
        }
    }

    /** Feeds the watcher's view to the state machine and carries out its decision. Worker only. */
    private fun tick() {
        val config = ConfigStore.get(this)
        val timing = Timing.of(config)
        machine.screen(watcher.screenOn)
        machine.observe(watcher.snapshot(config))
        machine.evaluate(timing)?.let { execute(it, config) }
        if (machine.running == null) cleanUpLeftovers(force = false)
    }

    private fun execute(action: Action, config: Config) {
        Log.i(TAG, "house: $action (observed ${machine.observation})")
        when (action) {
            is Action.Start -> startHouse(action.mode, config)
            is Action.Switch -> startHouse(action.mode, config)
            Action.Stop -> {
                val errors = house.stop(config)
                if (errors.isNotEmpty()) lastError = "stop: ${errors.joinToString("; ")}"
            }
        }
        updateNotification()
    }

    private fun startHouse(mode: HouseMode, config: Config) {
        if (projection == null) {
            machine.startFailed()
            lastError = "house mode not started: capture consent needed"
            return
        }
        try {
            house.start(mode, config)
            lastError = null
        } catch (e: Exception) {
            machine.startFailed()
            lastError = "house mode $mode failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError!!, e)
        }
    }

    /** Retries what a crashed run or a failed stop left in Music Assistant, at most once a minute. */
    private fun cleanUpLeftovers(force: Boolean) {
        if (!house.maDirty || house.mode != null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLeftoverAttempt < LEFTOVER_RETRY_MS) return
        lastLeftoverAttempt = now
        val errors = house.stop(ConfigStore.get(this))
        if (errors.isNotEmpty()) {
            lastError = "cleanup: ${errors.joinToString("; ")}"
        } else {
            Log.i(TAG, "music assistant leftovers restored")
        }
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
        consent = CONSENT_NEEDED
        post {
            if (house.mode != null) {
                machine.stopped()
                val errors = house.stop(ConfigStore.get(this))
                lastError = "capture consent lost; house mode stopped" +
                    if (errors.isEmpty()) "" else ": ${errors.joinToString("; ")}"
            }
            capture.stopAll()
            updateNotification()
        }
    }

    @Suppress("DEPRECATION")
    private fun parcelableIntent(intent: Intent, name: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(name, Intent::class.java)
        } else {
            intent.getParcelableExtra(name)
        }

    // --- foreground notification ------------------------------------------------------------

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
            when {
                ::house.isInitialized && house.mode != null -> R.string.notification_house
                projection != null -> R.string.notification_ready
                else -> R.string.notification_idle
            },
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
        val sender = streamer.sender
        return linkedMapOf(
            "app" to "ventriloquist",
            "version" to versionName(),
            "projection_ready" to (projection != null),
            "consent" to consent,
            "permissions" to linkedMapOf(
                "record_audio" to granted(Manifest.permission.RECORD_AUDIO),
                "write_secure_settings" to granted(Manifest.permission.WRITE_SECURE_SETTINGS),
                "notification_listener" to watcher.listenerGranted,
            ),
            "mode" to (house.mode?.name?.lowercase() ?: "idle"),
            "override" to machine.override.name.lowercase(),
            "target" to house.target,
            "hdmi_latency_ms" to reading.ms,
            "hdmi_latency_raw" to reading.raw,
            "film_latency_ms" to config.filmLatencyMs,
            "hdmi_volume" to audio.musicVolume(),
            "hdmi_volume_max" to audio.musicVolumeMax(),
            "volume_fixed" to audio.volumeFixed(),
            "surround_setting" to audio.surroundSetting(),
            "tv_muted" to latency.tvMuted,
            "dirty" to (latency.dirty || house.maDirty),
            "originals" to latency.originals(),
            "watcher" to linkedMapOf(
                "activity" to machine.observation.activity.name.lowercase(),
                "kind" to machine.observation.kind.name.lowercase(),
                "package" to machine.observation.packageName,
                "screen_on" to machine.screenOn,
                "quiet_hours" to machine.inQuietHours(Timing.of(config)),
                "blocked" to machine.blocked,
            ),
            "capture" to linkedMapOf(
                "running" to capture.running,
                "frames" to capture.framesCaptured,
                "levels" to capture.lastLevels?.toMap(),
                "error" to capture.lastError,
            ),
            "vban" to linkedMapOf(
                "running" to streamer.running,
                "target" to streamer.target,
                "packets_sent" to sender?.packetsSent,
                "frames_sent" to sender?.framesSent,
                "send_errors" to sender?.sendErrors,
                "resyncs" to sender?.resyncs,
                "last_send_error" to sender?.lastSendError,
            ),
            "music_assistant" to linkedMapOf(
                "dirty" to house.maDirty,
                "originals" to house.maOriginals(),
                "last_error" to house.lastMaError,
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
            "house_mode" to (house.mode?.name?.lowercase() ?: "idle"),
        )
    }

    override fun restore(): Map<String, Any?> = runOnWorker {
        val config = ConfigStore.get(this)
        val before = linkedMapOf("shield" to latency.originals(), "music_assistant" to house.maOriginals())
        machine.setOverride(ModeOverride.OFF, Timing.of(config))
        machine.stopped()
        val errors = house.stop(config)
        if (errors.isNotEmpty()) lastError = "restore: ${errors.joinToString("; ")}"
        updateNotification()
        linkedMapOf(
            "restored" to before,
            "errors" to errors,
            "dirty" to (latency.dirty || house.maDirty),
            "override" to machine.override.name.lowercase(),
        )
    }

    override fun setMode(mode: String): Map<String, Any?> = runOnWorker {
        val config = ConfigStore.get(this)
        val timing = Timing.of(config)
        val o = ModeOverride.valueOf(mode.uppercase())
        if (!machine.setOverride(o, timing)) {
            throw ApiException(409, "quiet hours (${config.quietStart} to ${config.quietEnd}): manual start refused")
        }
        lastError = null
        tick()
        linkedMapOf(
            "override" to machine.override.name.lowercase(),
            "mode" to (house.mode?.name?.lowercase() ?: "idle"),
            "target" to house.target,
            "blocked" to machine.blocked,
            "last_error" to lastError,
        )
    }

    override fun configure(update: Map<String, Any?>): Map<String, Any?> {
        val merged = ConfigStore.update(this, update)
        post { safeTick() }
        return linkedMapOf("updated" to update.keys.sorted(), "config" to merged.toMap(redactSecrets = true))
    }

    override fun stepLatency(deltaMs: Int): Map<String, Any?> = runOnWorker {
        val current = ConfigStore.get(this).filmLatencyMs
        val next = (current + deltaMs).coerceIn(0, Config.MAX_LATENCY_MS)
        ConfigStore.update(this, mapOf("film_latency_ms" to next.toLong()))
        val reading = house.applyFilmLatency(next)
        linkedMapOf(
            "film_latency_ms" to next,
            "previous_ms" to current,
            "applied" to (reading != null),
            "read_back_ms" to reading?.ms,
        )
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    /** Runs [block] on the worker thread and waits; rethrows its exception unchanged. */
    private fun <T> runOnWorker(block: () -> T): T {
        if (Thread.currentThread().name == WORKER_NAME) return block()
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
        const val WORKER_NAME = "ventriloquist-house"
        const val WORKER_TIMEOUT_S = 90L
        const val TICK_MS = 1000L
        const val LEFTOVER_RETRY_MS = 60_000L

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
