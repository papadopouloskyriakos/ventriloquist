package net.nuclearlighters.ventriloquist

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AppOpsManager
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
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import net.nuclearlighters.ventriloquist.capture.CaptureController
import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.config.ConfigStore
import net.nuclearlighters.ventriloquist.control.ApiException
import net.nuclearlighters.ventriloquist.control.ControlApi
import net.nuclearlighters.ventriloquist.control.ControlBackend
import net.nuclearlighters.ventriloquist.ha.HaNotifier
import net.nuclearlighters.ventriloquist.house.Action
import net.nuclearlighters.ventriloquist.house.HouseClock
import net.nuclearlighters.ventriloquist.house.HouseSession
import net.nuclearlighters.ventriloquist.house.HouseStateMachine
import net.nuclearlighters.ventriloquist.house.Rules
import net.nuclearlighters.ventriloquist.house.VbanStreamer
import net.nuclearlighters.ventriloquist.http.HttpServer
import net.nuclearlighters.ventriloquist.ma.MaClient
import net.nuclearlighters.ventriloquist.shield.AndroidAudioControls
import net.nuclearlighters.ventriloquist.shield.ShieldModule
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The long-running part of Ventriloquist. Runs in the foreground (type mediaProjection), holds
 * the playback-capture consent for as long as it lives, serves the control endpoint, runs the
 * automatic house mode, turns the remote's volume buttons into speaker volume while it runs,
 * and owns every change made to the box and to Music Assistant so that it can put them back.
 *
 * All house work (watcher ticks, starts and stops, volume, box and Music Assistant calls) runs
 * on one worker thread, so the endpoint, the watcher and the buttons never interleave. Home
 * Assistant notices go out on their own thread, so a slow Home Assistant delays nothing.
 */
class VentriloquistService : Service(), ControlBackend {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ventriloquist-house")
    }
    private lateinit var audio: AndroidAudioControls
    private lateinit var shield: ShieldModule
    private lateinit var house: HouseSession
    private lateinit var watcher: PlaybackWatcher
    private val capture = CaptureController(TAG)
    private val streamer = VbanStreamer(capture)
    private val ha = HaNotifier()
    private val haWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "ventriloquist-ha") }
    private val machine = HouseStateMachine(object : HouseClock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
        override fun minuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
    })
    private var server: HttpServer? = null
    private var lastLeftoverAttempt = 0L

    /** Read by the volume-key service on the main thread: house mode runs and wants the keys. */
    @Volatile
    private var takesVolumeKeys = false
    private val pendingSteps = AtomicInteger(0)
    private val volumeScheduled = AtomicBoolean(false)
    private var knownVolume: Int? = null
    private var knownVolumeAt = 0L

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var consent: String = CONSENT_NEEDED

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var holdsProjection = false

    /** True while a media projection (the capture consent) is held. */
    val projectionHeld: Boolean get() = projection != null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = AndroidAudioControls(this)
        val ledgers = PrefsStore.open(this, LEDGER_FILE)
        shield = ShieldModule(audio, Ledger(ledgers, "shield"))
        house = HouseSession(shield, Ledger(ledgers, "music_assistant"), { c -> MaClient(c.maUrl, c.maToken) }, streamer)
        startForegroundCompat()
        if (shield.dirty) {
            // A previous run did not finish its restore (crash, kill, power loss). The box goes
            // back at once (the TV gets its sound); Music Assistant follows on the worker.
            val leftovers = shield.originals()
            val errors = shield.restore()
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
        if (intent?.action != ACTION_STOP) {
            // Every start command re-asserts the foreground state with the microphone type. When
            // the command comes from a visible activity (ConsentActivity, MainActivity), this is
            // what gives the service while-in-use recording access for the rest of its life.
            // The service is never taken out of the foreground except to stop it.
            startForegroundCompat()
        }
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
            ACTION_REARM -> Log.i(TAG, "foreground re-asserted from a visible activity; capture access ${captureAccess()}")
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
        haWorker.shutdown()
        super.onDestroy()
    }

    private var shutDown = false

    private fun shutdown() {
        if (shutDown) return
        shutDown = true
        watcher.stop()
        takesVolumeKeys = false
        val errors = try {
            runOnWorker {
                val wasRunning = house.running
                machine.stopped()
                val errors = house.stop(ConfigStore.get(this))
                if (wasRunning) notifyHa(false, "service stopped")
                errors
            }
        } catch (e: Exception) {
            capture.stopAll()
            shield.restore() + "worker: ${e.message}"
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
        val rules = Rules.of(config)
        machine.screen(watcher.screenOn)
        machine.observe(watcher.snapshot())
        val quietBefore = machine.inQuietHours(rules)
        machine.evaluate(rules) { houseBlock(config) }?.let { execute(it, config, quietBefore) }
        takesVolumeKeys = house.running && config.volumeKeys
        if (!machine.running) cleanUpLeftovers(force = false)
    }

    private fun execute(action: Action, config: Config, quiet: Boolean) {
        Log.i(TAG, "house: $action (observed ${machine.observation})")
        when (action) {
            Action.START -> startHouse(config)
            Action.STOP -> stopHouse(
                config,
                when {
                    quiet -> "quiet hours"
                    !config.enabled -> "switched to the TV speakers"
                    !machine.screenOn -> "screen off"
                    else -> "playback ended"
                },
            )
        }
        updateNotification()
    }

    /** Stops house mode (or cleans up leftovers); returns the failures, also kept in lastError. */
    private fun stopHouse(config: Config, reason: String): List<String> {
        takesVolumeKeys = false
        val wasRunning = house.running
        val errors = house.stop(config)
        if (errors.isNotEmpty()) lastError = "stop: ${errors.joinToString("; ")}"
        if (wasRunning) notifyHa(false, reason)
        return errors
    }

    /** Tells Home Assistant (when a webhook is configured) on its own thread; never blocks. */
    private fun notifyHa(house: Boolean, reason: String) {
        val config = ConfigStore.get(this)
        if (config.haWebhookUrl.isEmpty()) return
        val event = linkedMapOf<String, Any?>(
            "event" to if (house) "house_started" else "house_stopped",
            "house" to house,
            "player" to config.playerId,
            "reason" to reason,
        )
        try {
            haWorker.execute {
                // one retry: a Home Assistant restart takes longer, its own poll covers that
                if (!ha.post(config.haWebhookUrl, event)) {
                    Thread.sleep(HA_RETRY_MS)
                    ha.post(config.haWebhookUrl, event)
                }
            }
        } catch (e: RejectedExecutionException) {
            // shutting down
        }
    }

    /** Why house mode cannot start now, or null; checked before any attempt so nothing churns. */
    private fun houseBlock(config: Config): String? {
        val missing = config.missingForHouse()
        if (missing.isNotEmpty()) return "not configured: ${missing.joinToString(", ")}"
        if (projection == null) return "capture consent needed"
        val access = captureAccess()
        if (access != ACCESS_GRANTED) return "capture access: $access"
        return null
    }

    /**
     * Whether the app may record right now: `granted`, `needs_visible_start` (the foreground
     * service was started while no activity of the app was visible, so Android withholds
     * while-in-use recording; open ConsentActivity once to fix it) or `no_record_audio_permission`.
     * Reads the evaluated RECORD_AUDIO app-op, which reflects the process's microphone capability.
     */
    @Suppress("DEPRECATION") // the non-deprecated variant needs API 36; this one exists since 29
    private fun captureAccess(): String {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return ACCESS_NO_PERMISSION
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), packageName)
        return if (mode == AppOpsManager.MODE_ALLOWED) ACCESS_GRANTED else ACCESS_NEEDS_VISIBLE_START
    }

    private fun startHouse(config: Config) {
        if (projection == null) {
            machine.startFailed()
            lastError = "house mode not started: capture consent needed"
            return
        }
        try {
            house.start(config)
            lastError = null
            knownVolume = null
            notifyHa(true, if (machine.manual) "started by hand" else "playback")
        } catch (e: Exception) {
            machine.startFailed()
            lastError = "house mode failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError!!, e)
        }
    }

    // --- volume buttons ---------------------------------------------------------------------

    /** For the volume-key service (main thread): whether to take the buttons now. */
    fun takesVolumeKeys(): Boolean = takesVolumeKeys

    /**
     * A volume button press (+1 up, -1 down; key repeats count too). Presses are gathered for
     * a moment and sent as one change, so holding a button does not queue a call per repeat.
     */
    fun volumeKey(steps: Int) {
        pendingSteps.addAndGet(steps)
        if (!volumeScheduled.compareAndSet(false, true)) return
        try {
            worker.schedule({
                volumeScheduled.set(false)
                val n = pendingSteps.getAndSet(0)
                if (n != 0) {
                    try {
                        val level = applyVolume(n)
                        toast(volumeText(level, ConfigStore.get(this)))
                    } catch (e: Exception) {
                        lastError = "volume: ${e.javaClass.simpleName}: ${e.message}"
                        toast(getString(R.string.volume_failed))
                    }
                }
            }, VOLUME_GATHER_MS, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            volumeScheduled.set(false)
        }
    }

    /** Worker only. Returns the new level, or null when house mode is not running. */
    private fun applyVolume(steps: Int): Int? {
        val config = ConfigStore.get(this)
        val now = SystemClock.elapsedRealtime()
        val known = if (now - knownVolumeAt < VOLUME_KNOWN_MS) knownVolume else null
        val level = house.nudgeVolume(steps, config, known)
        knownVolume = level
        knownVolumeAt = now
        return level
    }

    private fun volumeText(level: Int?, config: Config): String = when {
        level == null -> getString(R.string.volume_not_playing)
        level >= config.maxVolume -> getString(R.string.volume_level_max, level)
        else -> getString(R.string.volume_level, level)
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    /** Retries what a crashed run or a failed stop left in Music Assistant, at most once a minute. */
    private fun cleanUpLeftovers(force: Boolean) {
        if (!house.maDirty || house.running) return
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
            if (house.running) {
                machine.stopped()
                takesVolumeKeys = false
                val errors = house.stop(ConfigStore.get(this))
                notifyHa(false, "capture consent lost")
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
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 checks each type when it is requested: the projection type needs a
            // consent, the microphone type a visible app. Ask for what applies, fall back without
            // the microphone when it is refused (capture then works only while the app is shown).
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (holdsProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            try {
                startForeground(NOTIFICATION_ID, buildNotification(), base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (e: SecurityException) {
                Log.w(TAG, "microphone foreground type refused: ${e.message}")
                startForeground(NOTIFICATION_ID, buildNotification(), base)
            }
        } else {
            // Android 11 to 13: the types only need to be declared; whether recording is allowed
            // in the background is decided by the visibility of the app when the service is started.
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }
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
                ::house.isInitialized && house.running -> R.string.notification_house
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
        val reading = shield.readLatency()
        val config = ConfigStore.get(this)
        val rules = Rules.of(config)
        val sender = streamer.sender
        return linkedMapOf(
            "app" to "ventriloquist",
            "version" to versionName(),
            "projection_ready" to (projection != null),
            "consent" to consent,
            "capture_access" to captureAccess(),
            "permissions" to linkedMapOf(
                "record_audio" to granted(Manifest.permission.RECORD_AUDIO),
                "write_secure_settings" to granted(Manifest.permission.WRITE_SECURE_SETTINGS),
                "notification_listener" to watcher.listenerGranted,
                "volume_keys_service" to VolumeKeyService.connected,
            ),
            "mode" to if (house.running) "house" else "idle",
            "house" to house.running,
            "enabled" to config.enabled,
            "manual" to machine.manual,
            "target" to house.target,
            "volume" to knownVolume,
            "takes_volume_keys" to takesVolumeKeys,
            "box_muted" to shield.boxMuted,
            "stream_muted" to audio.musicMuted(),
            "hdmi_volume" to audio.musicVolume(),
            "hdmi_volume_max" to audio.musicVolumeMax(),
            "surround_setting" to audio.surroundSetting(),
            "hdmi_latency_ms" to reading.ms,
            "dirty" to (shield.dirty || house.maDirty),
            "originals" to shield.originals(),
            "watcher" to linkedMapOf(
                "activity" to machine.observation.activity.name.lowercase(),
                "package" to machine.observation.packageName,
                "screen_on" to machine.screenOn,
                "quiet_hours" to machine.inQuietHours(rules),
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
            "home_assistant" to linkedMapOf(
                "webhook" to config.haWebhookUrl.isNotEmpty(),
                "last_error" to ha.lastError,
            ),
            "last_error" to lastError,
            "config" to config.toMap(redactSecrets = true),
        )
    }

    override fun captureTest(seconds: Int): Map<String, Any?> {
        if (projection == null) throw IllegalStateException("consent needed: no media projection held")
        val access = captureAccess()
        val levels = try {
            capture.captureTest(seconds)
        } catch (e: IllegalStateException) {
            lastError = "capture test: ${e.message} (capture_access: $access)"
            throw IllegalStateException(lastError, e)
        }
        return linkedMapOf(
            "seconds" to seconds,
            "levels" to levels.toMap(),
            "stream_muted" to audio.musicMuted(),
            "house" to house.running,
            "capture_access" to access,
        )
    }

    override fun restore(): Map<String, Any?> = runOnWorker {
        val config = ConfigStore.get(this)
        val before = linkedMapOf("shield" to shield.originals(), "music_assistant" to house.maOriginals())
        machine.stopped()
        val errors = stopHouse(config, "restore requested")
        updateNotification()
        linkedMapOf(
            "restored" to before,
            "errors" to errors,
            "dirty" to (shield.dirty || house.maDirty),
        )
    }

    override fun setMode(mode: String): Map<String, Any?> = runOnWorker {
        when (mode) {
            "auto", "off" -> {
                ConfigStore.update(this, mapOf("enabled" to (mode == "auto")))
                machine.stopNow()
            }
            "on" -> {
                val config = ConfigStore.get(this)
                if (!machine.startNow(Rules.of(config))) {
                    throw ApiException(409, "quiet hours (${config.quietStart} to ${config.quietEnd}): manual start refused")
                }
            }
            else -> throw IllegalArgumentException("unknown mode $mode")
        }
        lastError = null
        tick()
        linkedMapOf(
            "enabled" to ConfigStore.get(this).enabled,
            "manual" to machine.manual,
            "house" to house.running,
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

    override fun nudgeVolume(steps: Int): Map<String, Any?> = runOnWorker {
        val level = applyVolume(steps)
        linkedMapOf("house" to house.running, "volume" to level, "steps" to steps)
    }

    override fun players(): Map<String, Any?> {
        val config = ConfigStore.get(this)
        if (config.maUrl.isEmpty() || config.maToken.isEmpty()) {
            throw IllegalStateException("not configured: ma_url, ma_token")
        }
        val players = MaClient(config.maUrl, config.maToken).players()
        return linkedMapOf(
            "players" to players.map {
                linkedMapOf("id" to it.id, "name" to it.name, "group" to it.group, "available" to it.available)
            },
            "selected" to config.playerId,
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
        const val HA_RETRY_MS = 2_000L
        const val VOLUME_GATHER_MS = 120L
        const val VOLUME_KNOWN_MS = 5_000L

        const val ACTION_START = "net.nuclearlighters.ventriloquist.action.START"
        const val ACTION_STOP = "net.nuclearlighters.ventriloquist.action.STOP"
        const val ACTION_BOOT = "net.nuclearlighters.ventriloquist.action.BOOT"
        const val ACTION_REARM = "net.nuclearlighters.ventriloquist.action.REARM"
        const val ACTION_PROJECTION = "net.nuclearlighters.ventriloquist.action.PROJECTION"
        const val ACTION_CONSENT_DENIED = "net.nuclearlighters.ventriloquist.action.CONSENT_DENIED"
        const val ACTION_CONFIG_CHANGED = "net.nuclearlighters.ventriloquist.action.CONFIG_CHANGED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        const val CONSENT_NEEDED = "needed"
        const val CONSENT_READY = "ready"
        const val CONSENT_DENIED = "denied"

        const val ACCESS_GRANTED = "granted"
        const val ACCESS_NEEDS_VISIBLE_START = "needs_visible_start"
        const val ACCESS_NO_PERMISSION = "no_record_audio_permission"

        const val CHANNEL_ID = "ventriloquist"
        const val NOTIFICATION_ID = 1

        @Volatile
        var instance: VentriloquistService? = null
            private set
    }
}
