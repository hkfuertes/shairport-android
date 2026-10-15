package com.hkfuertes.shairport.satellites

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import java.net.Inet4Address

/** The settings, and who is connected (SatellitesService's status), live. */
@Suppress("DEPRECATION") // classic preferences, like Shairport's
class MainActivity : PreferenceActivity() {
    private val statusListener: () -> Unit = { runOnUiThread(::refresh) }
    private lateinit var status: PreferenceCategory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
        status = findPreference("status") as PreferenceCategory
        // Started from the tap itself: Android forbids starting foreground services from the background.
        findPreference(SatellitesService.ENABLED).setOnPreferenceChangeListener { _, value ->
            if (value == true) SatellitesService.start(this) // off: the service stops itself
            true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        if (SatellitesService.preferences(this).getBoolean(SatellitesService.ENABLED, false)) SatellitesService.start(this)
    }

    override fun onResume() {
        super.onResume()
        SatellitesService.addListener(statusListener)
        refresh()
    }

    override fun onPause() {
        SatellitesService.removeListener(statusListener)
        super.onPause()
    }

    private fun refresh() {
        if (isFinishing || isDestroyed) return
        // ponytail: rebuilt on every change; a few rows, no diffing needed.
        status.removeAll()
        status.addPreference(row(getString(R.string.status_shairport), SatellitesService.summary(this)))
        if (SatellitesService.link == SatellitesService.Link.OFF) return
        status.addPreference(
            row(getString(R.string.status_server), getString(R.string.server, wifiAddress() ?: getString(R.string.no_wifi), Satellites.PORT, Protocol.SERVICE)),
        )
        for (player in SatellitesService.players) {
            status.addPreference(row(player.name, listOf(player.address, player.client).filter { it.isNotEmpty() }.joinToString(" · ")))
        }
    }

    private fun row(title: String, summary: String?) = Preference(this).apply {
        this.title = title
        this.summary = summary
        isPersistent = false
        isSelectable = false
    }

    /** Where players reach the server, for those given an address. */
    @Suppress("DEPRECATION") // allNetworks: simplest way to read the current Wi-Fi address once
    private fun wifiAddress(): String? {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstNotNullOfOrNull { network ->
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
            else connectivity.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        }
    }
}
