package com.hkfuertes.shairport

import android.Manifest
import android.preference.PreferenceActivity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.SwitchPreference
import android.widget.Toast
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION") // Classic XML preferences are deliberate for this background app.
class MainActivity : PreferenceActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var preferences: SharedPreferences
    private lateinit var rootPreference: Preference
    private lateinit var statusCategory: PreferenceCategory
    private val statusListener: () -> Unit = { runOnUiThread(::refreshStatus) }
    private enum class Root { GRANTED, DENIED, MISSING }
    private var rootCheckRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Prefs.get(this) // stores defaults (device name, ...) before the screen reads them
        addPreferencesFromResource(R.xml.preferences)
        preferences.registerOnSharedPreferenceChangeListener(this)
        rootPreference = findPreference(PREF_ROOT_ACCESS)
        rootPreference.setOnPreferenceClickListener {
            checkRoot()
            true
        }
        // Only AirPlay 2 needs root: the switch turns on once su is granted (Magisk may prompt).
        findPreference(Prefs.AIRPLAY_2).setOnPreferenceChangeListener { preference, value ->
            if (value != true) return@setOnPreferenceChangeListener true
            checkRoot { (preference as SwitchPreference).isChecked = true }
            false
        }
        // Started from the tap itself: Android forbids foreground services started from the
        // background, which a preference listener may be (e.g. adb changes it while we're paused).
        findPreference(Prefs.RECEIVER_ENABLED).setOnPreferenceChangeListener { _, value ->
            if (value == true) ReceiverService.start(this) // turning off: the service stops itself
            true
        }
        statusCategory = findPreference(PREF_STATUS) as PreferenceCategory
        refreshModel()

        requestNotificationPermission()
        if (preferences.getBoolean(Prefs.RECEIVER_ENABLED, true)) ReceiverService.start(this)
        // Asking su without need would make Magisk prompt users who never wanted AirPlay 2.
        if (preferences.getBoolean(Prefs.AIRPLAY_2, false)) checkRoot()
        else rootPreference.setSummary(R.string.root_access_not_requested)
    }

    override fun onResume() {
        super.onResume()
        EngineStatus.addListener(statusListener)
        refreshStatus()
    }

    override fun onPause() {
        EngineStatus.removeListener(statusListener)
        super.onPause()
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    private fun refreshStatus() {
        if (isFinishing || isDestroyed) return
        fun summary(key: String, text: String) { statusCategory.findPreference(key).summary = text }
        summary(
            "status_shairport",
            getString(
                when {
                    !EngineStatus.shairport -> R.string.status_stopped
                    EngineStatus.airplay2 -> R.string.status_running_airplay2
                    else -> R.string.status_running_classic
                },
            ),
        )
        summary(
            "status_nqptp",
            getString(
                when {
                    !preferences.getBoolean(Prefs.AIRPLAY_2, false) -> R.string.status_nqptp_off
                    EngineStatus.nqptp -> R.string.status_running
                    else -> R.string.status_stopped
                },
            ),
        )
        summary(
            "status_mdns",
            if (EngineStatus.shairport && EngineStatus.advertising) {
                val name = preferences.getString(Prefs.SERVER_NAME, null)?.takeIf { it.isNotBlank() }
                    ?: Prefs.deviceName(this)
                getString(R.string.status_advertising, name, EngineStatus.address ?: getString(R.string.status_no_wifi))
            } else {
                getString(R.string.status_not_advertising)
            },
        )
        summary(
            "status_playback",
            EngineStatus.source?.let { getString(R.string.status_playing, it) } ?: getString(R.string.status_idle),
        )
    }

    // Configuration changes are applied by the running service itself (it listens too).
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            Prefs.AIRPLAY_2 -> {
                refreshModel()
                refreshStatus()
            }
            Prefs.MODEL -> refreshModel()
            // The tile, the notification's "Stop" or adb may change it while this screen is open.
            Prefs.RECEIVER_ENABLED -> (findPreference(Prefs.RECEIVER_ENABLED) as SwitchPreference).isChecked =
                sharedPreferences.getBoolean(Prefs.RECEIVER_ENABLED, true)
        }
    }

    /** Asks for su (Magisk prompts if it has no saved answer); [onGranted] runs if granted. */
    private fun checkRoot(onGranted: (() -> Unit)? = null) {
        if (rootCheckRunning) return
        rootCheckRunning = true
        rootPreference.isEnabled = false
        rootPreference.setSummary(R.string.root_access_checking)
        Thread({
            val root = rootStatus()
            runOnUiThread { applyRootResult(root, onGranted) }
        }, "root-check").start()
    }

    private fun applyRootResult(root: Root, onGranted: (() -> Unit)?) {
        if (isFinishing || isDestroyed) return
        rootCheckRunning = false
        rootPreference.isEnabled = root != Root.GRANTED // nothing left to request once granted
        rootPreference.setSummary(
            when (root) {
                Root.GRANTED -> R.string.root_access_granted
                Root.DENIED -> R.string.root_access_denied
                Root.MISSING -> R.string.root_access_missing
            },
        )
        if (root == Root.GRANTED) onGranted?.invoke()
        else if (onGranted != null) Toast.makeText(this, R.string.root_required, Toast.LENGTH_LONG).show()
    }

    /**
     * Classic AirPlay advertises the generic model (ReceiverService); the choice is AirPlay 2's.
     * The Home app's hub removes any accessory whose model is a HomePod, so say so next to it.
     */
    private fun refreshModel() {
        val airplay2 = preferences.getBoolean(Prefs.AIRPLAY_2, false)
        val homePod = preferences.getString(Prefs.MODEL, null).orEmpty().startsWith("AudioAccessory")
        findPreference(Prefs.MODEL).apply {
            isEnabled = airplay2
            summary = when {
                !airplay2 -> getString(R.string.model_classic_summary)
                homePod -> getString(R.string.model_homepod_summary)
                else -> "%s"
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    /**
     * `su` itself makes Magisk show its grant prompt, but only while Magisk has no saved answer:
     * a saved "deny" (also left by a prompt that timed out) is applied silently, no prompt.
     */
    private fun rootStatus(): Root {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(ROOT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return Root.DENIED
            process.inputStream.bufferedReader().use { output ->
                if (process.exitValue() == 0 && output.readText().contains("uid=0")) Root.GRANTED
                else Root.DENIED
            }
        } catch (_: java.io.IOException) {
            Root.MISSING // no su binary at all
        } catch (_: Exception) {
            Root.DENIED
        } finally {
            process?.destroy()
        }
    }

    companion object {
        private const val PREF_ROOT_ACCESS = "root_access"
        private const val PREF_STATUS = "status"
        private const val ROOT_CHECK_TIMEOUT_SECONDS = 30L
    }
}
