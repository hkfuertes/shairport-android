package com.hkfuertes.shairport

import android.Manifest
import android.preference.PreferenceActivity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceGroup
import android.widget.Toast
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION") // Classic XML preferences are deliberate for this background app.
class MainActivity : PreferenceActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var preferences: SharedPreferences
    private lateinit var rootPreference: Preference
    private lateinit var advanced: PreferenceCategory
    private lateinit var showAdvanced: Preference
    private var advancedShown = false
    private var rootGranted = false
    private enum class Root { GRANTED, DENIED, MISSING }
    private var rootCheckRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Prefs.get(this) // stores defaults (device name, ...) before the screen reads them
        addPreferencesFromResource(R.xml.preferences)
        preferences.registerOnSharedPreferenceChangeListener(this)
        rootPreference = findPreference(PREF_ROOT_ACCESS)
        rootPreference.setOnPreferenceClickListener {
            requestRoot()
            true
        }
        advanced = findPreference(PREF_ADVANCED) as PreferenceCategory
        advanced.findPreference(Prefs.PORT).setOnPreferenceChangeListener { _, value ->
            if (Prefs.isValidPort(value.toString())) {
                true
            } else {
                Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show()
                false
            }
        }
        showAdvanced = findPreference(PREF_SHOW_ADVANCED)
        showAdvanced.setOnPreferenceClickListener {
            setAdvancedShown(!advancedShown)
            true
        }
        setAdvancedShown(false)

        setProtectedPreferencesEnabled(false)
        requestRoot()
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    // Configuration changes are applied by the running service itself (it listens too).
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key != Prefs.RECEIVER_ENABLED) return
        if (rootGranted && sharedPreferences.getBoolean(Prefs.RECEIVER_ENABLED, true)) {
            ReceiverService.start(this)
        } else {
            ReceiverService.stop(this)
        }
    }

    private fun requestRoot() {
        if (rootCheckRunning) return

        rootCheckRunning = true
        rootPreference.isEnabled = false
        rootPreference.setSummary(R.string.root_access_checking)
        setProtectedPreferencesEnabled(false)
        Thread({
            val root = rootStatus()
            runOnUiThread { applyRootResult(root) }
        }, "root-check").start()
    }

    private fun applyRootResult(root: Root) {
        if (isFinishing || isDestroyed) return

        rootCheckRunning = false
        val granted = root == Root.GRANTED
        rootGranted = granted
        rootPreference.isEnabled = !granted // nothing left to request once root is granted
        rootPreference.setSummary(
            when (root) {
                Root.GRANTED -> R.string.root_access_granted
                Root.DENIED -> R.string.root_access_denied
                Root.MISSING -> R.string.root_access_missing
            },
        )
        setProtectedPreferencesEnabled(granted)

        if (granted) {
            requestNotificationPermission()
            if (preferences.getBoolean(Prefs.RECEIVER_ENABLED, true)) {
                ReceiverService.start(this)
            }
        } else {
            ReceiverService.stop(this)
        }
    }

    /** Framework preferences can't be hidden, so the Advanced category is removed and re-added. */
    private fun setAdvancedShown(shown: Boolean) {
        advancedShown = shown
        if (shown) preferenceScreen.addPreference(advanced) else preferenceScreen.removePreference(advanced)
        showAdvanced.setTitle(if (shown) R.string.hide_advanced else R.string.show_advanced)
    }

    private fun setProtectedPreferencesEnabled(enabled: Boolean) {
        val screen = preferenceScreen
        for (i in 0 until screen.preferenceCount) {
            setPreferenceEnabled(screen.getPreference(i), enabled)
        }
        if (!advancedShown) setPreferenceEnabled(advanced, enabled) // off-screen, but kept in step
    }

    private fun setPreferenceEnabled(preference: Preference, enabled: Boolean) {
        if (preference !== rootPreference) preference.isEnabled = enabled
        if (preference is PreferenceGroup) {
            for (i in 0 until preference.preferenceCount) {
                setPreferenceEnabled(preference.getPreference(i), enabled)
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
        private const val PREF_ADVANCED = "advanced"
        private const val PREF_SHOW_ADVANCED = "show_advanced"
        private const val ROOT_CHECK_TIMEOUT_SECONDS = 30L
    }
}
