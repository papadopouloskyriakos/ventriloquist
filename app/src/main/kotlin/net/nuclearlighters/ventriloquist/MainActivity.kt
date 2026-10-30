package net.nuclearlighters.ventriloquist

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.config.ConfigStore

/**
 * The settings screen, made for a remote: every row is a button, OK opens its choices. It shows
 * what the app is doing and lets the household choose the home speakers or the TV speakers,
 * the speaker group, the volume buttons and the quiet hours. Addresses and tokens are set at
 * provisioning time and are not shown.
 */
class MainActivity : Activity() {

    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var footer: TextView
    private val rows = LinkedHashMap<String, Button>()

    /** Names of the Music Assistant players, by id, once fetched. */
    @Volatile
    private var playerNames: Map<String, String> = emptyMap()

    private val refresh = object : Runnable {
        override fun run() {
            render()
            headline.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        startForegroundService(
            Intent(this, VentriloquistService::class.java).setAction(VentriloquistService.ACTION_START),
        )
        rows.values.first().requestFocus()
    }

    override fun onResume() {
        super.onResume()
        headline.post(refresh)
        loadPlayers(show = false)
    }

    override fun onPause() {
        headline.removeCallbacks(refresh)
        super.onPause()
    }

    // --- layout -----------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, color: Int): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(32), dp(64), dp(32))
        }
        column.addView(text(26f, Color.WHITE).apply { text = getString(R.string.app_name) })
        headline = text(20f, Color.WHITE).apply { setPadding(0, dp(12), 0, 0) }
        detail = text(15f, Color.rgb(0xB0, 0xBE, 0xC5)).apply { setPadding(0, dp(4), 0, dp(16)) }
        column.addView(headline)
        column.addView(detail)
        row(column, ROW_SOUND) { chooseSound() }
        row(column, ROW_SPEAKERS) { loadPlayers(show = true) }
        row(column, ROW_KEYS) { chooseVolumeKeys() }
        row(column, ROW_STEP) { chooseStep() }
        row(column, ROW_MAX) { chooseMax() }
        row(column, ROW_TV) { chooseTvSound() }
        row(column, ROW_QUIET) { chooseQuiet() }
        row(column, ROW_STOP) { chooseStopDelay() }
        row(column, ROW_CONSENT) { startActivity(Intent(this, ConsentActivity::class.java)) }
        footer = text(13f, Color.rgb(0x90, 0xA4, 0xAE)).apply { setPadding(0, dp(16), 0, 0) }
        column.addView(footer)
        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x1F, 0x29, 0x33))
            addView(column)
        }
    }

    private fun row(parent: LinearLayout, key: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            // The theme's focus tint is barely visible on a TV: the row the remote is on gets a
            // light background and dark text, so it can be seen from the sofa.
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(FOCUSED_BG))
                addState(intArrayOf(), rounded(ROW_BG))
            }
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(FOCUSED_TEXT, Color.WHITE),
                ),
            )
            stateListAnimator = null
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener { onClick() }
        }
        parent.addView(
            b,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) },
        )
        rows[key] = b
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6).toFloat()
    }

    // --- rendering --------------------------------------------------------------------------

    private fun render() {
        val c = ConfigStore.get(this)
        val s = VentriloquistService.instance?.status()
        val speakers = speakerName(c)
        when {
            s == null -> {
                headline.text = getString(R.string.state_starting)
                detail.text = ""
            }
            s["house"] == true -> {
                headline.text = getString(R.string.state_playing, speakers)
                detail.text = if (s["takes_volume_keys"] == true) getString(R.string.detail_keys) else ""
            }
            !c.enabled -> {
                headline.text = getString(R.string.state_tv)
                detail.text = getString(R.string.detail_tv)
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val watcher = s["watcher"] as Map<String, Any?>
                headline.text = getString(R.string.state_ready, speakers)
                detail.text = when (val b = watcher["blocked"] as String?) {
                    null -> getString(R.string.detail_ready)
                    "quiet hours" -> getString(R.string.detail_quiet, c.quietEnd)
                    else -> getString(R.string.detail_blocked, b)
                }
            }
        }
        label(ROW_SOUND, R.string.row_sound, getString(if (c.enabled) R.string.sound_home else R.string.sound_tv))
        label(ROW_SPEAKERS, R.string.row_speakers, speakers)
        label(ROW_KEYS, R.string.row_keys, getString(if (c.volumeKeys) R.string.keys_speakers else R.string.keys_tv))
        label(ROW_STEP, R.string.row_step, getString(R.string.percent, c.volumeStep))
        label(ROW_MAX, R.string.row_max, getString(R.string.percent, c.maxVolume))
        label(ROW_TV, R.string.row_tv, getString(if (c.muteBox) R.string.tv_muted else R.string.tv_on))
        label(ROW_QUIET, R.string.row_quiet, quietText(c))
        label(ROW_STOP, R.string.row_stop, minutesText(c.stopDelayS))
        val consentReady = s?.get("projection_ready") == true && s["capture_access"] == "granted"
        label(ROW_CONSENT, R.string.row_consent, getString(if (consentReady) R.string.consent_ok else R.string.consent_needed))
        footer.text = if (s == null) "" else getString(
            R.string.footer,
            s["version"].toString(),
            (s["music_assistant"] as? Map<*, *>)?.get("last_error")?.toString() ?: getString(R.string.none),
            s["last_error"]?.toString() ?: getString(R.string.none),
        )
    }

    private fun label(key: String, title: Int, value: String) {
        rows[key]?.text = getString(R.string.row_format, getString(title), value)
    }

    private fun speakerName(c: Config): String = when {
        c.playerId.isEmpty() -> getString(R.string.speakers_unset)
        else -> playerNames[c.playerId] ?: c.playerId
    }

    private fun quietText(c: Config): String =
        if (c.quietStart == c.quietEnd) getString(R.string.quiet_off) else getString(R.string.quiet_range, c.quietStart, c.quietEnd)

    private fun minutesText(seconds: Int): String =
        if (seconds < 60) getString(R.string.seconds, seconds) else getString(R.string.minutes, seconds / 60)

    // --- choices ----------------------------------------------------------------------------

    /** A single-choice dialog; [apply] runs off the main thread, then the screen refreshes. */
    private fun <T> choose(title: Int, labels: List<String>, values: List<T>, current: T, apply: (T) -> Unit) {
        val checked = values.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val value = values[which]
                Thread {
                    val error = try {
                        apply(value)
                        null
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                    runOnUiThread {
                        if (error != null) detail.text = getString(R.string.detail_blocked, error)
                        render()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun configure(update: Map<String, Any?>) {
        val service = VentriloquistService.instance
        if (service != null) {
            service.configure(update)
        } else {
            ConfigStore.update(this, update)
        }
    }

    private fun chooseSound() {
        val c = ConfigStore.get(this)
        choose(
            R.string.row_sound,
            listOf(getString(R.string.sound_home_long), getString(R.string.sound_tv_long)),
            listOf(true, false),
            c.enabled,
        ) { on ->
            val service = VentriloquistService.instance
            if (service != null) service.setMode(if (on) "auto" else "off") else configure(mapOf("enabled" to on))
        }
    }

    private fun loadPlayers(show: Boolean) {
        Thread {
            val result = try {
                @Suppress("UNCHECKED_CAST")
                val list = VentriloquistService.instance?.players()?.get("players") as List<Map<String, Any?>>?
                list ?: emptyList()
            } catch (e: Exception) {
                if (show) runOnUiThread { detail.text = getString(R.string.detail_blocked, e.message ?: "?") }
                return@Thread
            }
            playerNames = result.associate { it["id"] as String to it["name"] as String }
            runOnUiThread {
                render()
                if (show && result.isNotEmpty()) {
                    val ids = result.map { it["id"] as String }
                    val labels = result.map {
                        val name = it["name"] as String
                        when {
                            it["available"] == false -> getString(R.string.player_unavailable, name)
                            it["group"] == true -> getString(R.string.player_group, name)
                            else -> name
                        }
                    }
                    choose(R.string.row_speakers, labels, ids, ConfigStore.get(this).playerId) { id ->
                        configure(mapOf("player_id" to id))
                    }
                }
            }
        }.start()
    }

    private fun chooseVolumeKeys() = choose(
        R.string.row_keys,
        listOf(getString(R.string.keys_speakers_long), getString(R.string.keys_tv_long)),
        listOf(true, false),
        ConfigStore.get(this).volumeKeys,
    ) { configure(mapOf("volume_keys" to it)) }

    private fun chooseStep() {
        val steps = listOf(1, 2, 3, 5)
        choose(R.string.row_step, steps.map { getString(R.string.percent, it) }, steps, ConfigStore.get(this).volumeStep) {
            configure(mapOf("volume_step" to it))
        }
    }

    private fun chooseMax() {
        val levels = listOf(20, 30, 40, 50, 60, 80, 100)
        choose(R.string.row_max, levels.map { getString(R.string.percent, it) }, levels, ConfigStore.get(this).maxVolume) {
            configure(mapOf("max_volume" to it))
        }
    }

    private fun chooseTvSound() = choose(
        R.string.row_tv,
        listOf(getString(R.string.tv_muted_long), getString(R.string.tv_on_long)),
        listOf(true, false),
        ConfigStore.get(this).muteBox,
    ) { configure(mapOf("mute_box" to it)) }

    private fun chooseQuiet() {
        val c = ConfigStore.get(this)
        val ranges = listOf("22:00" to "08:00", "23:00" to "08:00", "23:00" to "09:00")
        val values = (ranges + if ((c.quietStart to c.quietEnd) in ranges) emptyList() else listOf(c.quietStart to c.quietEnd))
        val labels = values.map { (s, e) -> if (s == e) getString(R.string.quiet_off) else getString(R.string.quiet_range, s, e) }
        choose(R.string.row_quiet, labels, values, c.quietStart to c.quietEnd) { (s, e) ->
            configure(mapOf("quiet_start" to s, "quiet_end" to e))
        }
    }

    private fun chooseStopDelay() {
        val delays = listOf(60, 180, 300, 600, 900)
        choose(R.string.row_stop, delays.map { minutesText(it) }, delays, ConfigStore.get(this).stopDelayS) {
            configure(mapOf("stop_delay_s" to it))
        }
    }

    companion object {
        const val REFRESH_MS = 2000L
        val ROW_BG = Color.rgb(0x37, 0x47, 0x4F)
        val FOCUSED_BG = Color.rgb(0xEC, 0xEF, 0xF1)
        val FOCUSED_TEXT = Color.rgb(0x1F, 0x29, 0x33)
        const val ROW_SOUND = "sound"
        const val ROW_SPEAKERS = "speakers"
        const val ROW_KEYS = "keys"
        const val ROW_STEP = "step"
        const val ROW_MAX = "max"
        const val ROW_TV = "tv"
        const val ROW_QUIET = "quiet"
        const val ROW_STOP = "stop"
        const val ROW_CONSENT = "consent"
    }
}
