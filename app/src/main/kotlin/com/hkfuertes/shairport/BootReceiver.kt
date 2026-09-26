package com.hkfuertes.shairport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Start at boot". ReceiverService is a connectedDevice foreground service because Android 15
 * forbids starting mediaPlayback ones from BOOT_COMPLETED. With a secure lock screen the
 * broadcast only arrives after the first unlock.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val preferences = Prefs.get(context)
        if (preferences.getBoolean(Prefs.START_AT_BOOT, false) && preferences.getBoolean(Prefs.RECEIVER_ENABLED, true)) {
            ReceiverService.start(context)
        }
    }
}
