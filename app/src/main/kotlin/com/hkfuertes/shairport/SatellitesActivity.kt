package com.hkfuertes.shairport

import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory

/** Where satellites should connect, and who is connected (EngineStatus.satellites), live. */
@Suppress("DEPRECATION") // classic preferences, like MainActivity
class SatellitesActivity : PreferenceActivity() {
    private val statusListener: () -> Unit = { runOnUiThread(::refresh) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceScreen = preferenceManager.createPreferenceScreen(this)
    }

    override fun onResume() {
        super.onResume()
        EngineStatus.addListener(statusListener)
        refresh()
    }

    override fun onPause() {
        EngineStatus.removeListener(statusListener)
        super.onPause()
    }

    private fun refresh() {
        if (isFinishing || isDestroyed) return
        // ponytail: rebuilt on every change; a few rows, no diffing needed.
        val screen = preferenceScreen.apply { removeAll() }
        val address = EngineStatus.address
        screen.addPreference(
            row(
                getString(R.string.satellites_server_title),
                when {
                    !Prefs.get(this).getBoolean(Prefs.SATELLITES, false) -> getString(R.string.satellites_off)
                    !EngineStatus.shairport -> getString(R.string.satellites_not_running)
                    address == null -> getString(R.string.status_no_wifi)
                    else -> getString(R.string.satellites_server, address, Satellites.PORT)
                },
            ),
        )
        val connected = PreferenceCategory(this).apply { setTitle(R.string.satellites_connected) }
        screen.addPreference(connected)
        if (EngineStatus.satellites.isEmpty()) connected.addPreference(row(getString(R.string.satellites_none), null))
        for (satellite in EngineStatus.satellites) {
            connected.addPreference(
                row(satellite.name, listOf(satellite.address, satellite.client).filter { it.isNotEmpty() }.joinToString(" · ")),
            )
        }
    }

    private fun row(title: String, summary: String?) = Preference(this).apply {
        this.title = title
        this.summary = summary
        isPersistent = false
        isSelectable = false
    }
}
