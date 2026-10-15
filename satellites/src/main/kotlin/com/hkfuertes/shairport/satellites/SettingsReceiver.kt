package com.hkfuertes.shairport.satellites

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * adb (README): CONFIGURE_SETTINGS sets one setting (`--es key`, `--ez value`); it and GET_STATUS
 * answer with the state as one URL-encoded line. Protected by android.permission.DUMP (manifest).
 * Turning it on takes `am start-foreground-service` (Android won't start one from here).
 */
class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = SatellitesService.preferences(context)
        if (intent.action == ACTION_CONFIGURE) {
            val key = intent.getStringExtra("key")
            @Suppress("DEPRECATION") // Bundle.get: the value's type is what we check
            val value = intent.extras?.get("value")
            if (key !in SatellitesService.SETTINGS || value !is Boolean) {
                resultCode = Activity.RESULT_CANCELED
                resultData = "key: one of ${SatellitesService.SETTINGS}; value: a boolean (--ez)"
                return
            }
            preferences.edit().putBoolean(key, value).commit()
        }
        val status = Uri.Builder()
            .appendQueryParameter("state", SatellitesService.link.name.lowercase())
            .appendQueryParameter("players", SatellitesService.players.size.toString())
        SatellitesService.SETTINGS.forEach { status.appendQueryParameter(it, preferences.getBoolean(it, false).toString()) }
        resultCode = Activity.RESULT_OK
        resultData = status.build().encodedQuery
    }

    companion object {
        const val ACTION_CONFIGURE = "com.hkfuertes.shairport.satellites.CONFIGURE_SETTINGS"
    }
}

/** Starts the satellites after a reboot when they are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            SatellitesService.preferences(context).getBoolean(SatellitesService.ENABLED, false)
        ) {
            SatellitesService.start(context)
        }
    }
}
