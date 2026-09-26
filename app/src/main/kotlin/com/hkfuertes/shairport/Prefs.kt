package com.hkfuertes.shairport

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager
import android.provider.Settings

/** Setting keys and defaults, shared by the settings screen, the service and adb (SettingsReceiver). */
@Suppress("DEPRECATION") // framework PreferenceManager, like the classic settings screen
object Prefs {
    const val RECEIVER_ENABLED = "receiver_enabled"
    const val SERVER_NAME = "server_name"
    const val MODEL = "model"
    const val NETWORK_INTERFACE = "network_interface"
    const val PORT = "port"
    const val START_AT_BOOT = "start_at_boot"
    const val PLAYBACK_MODE = "playback_mode"

    /** Typed as the settings screen stores them (EditTextPreference keeps Strings). */
    fun defaults(context: Context): Map<String, Any> = mapOf(
        RECEIVER_ENABLED to true,
        SERVER_NAME to deviceName(context),
        MODEL to "AudioAccessory1,1",
        NETWORK_INTERFACE to "wlan0",
        PORT to "7000",
        START_AT_BOOT to false,
        PLAYBACK_MODE to "stereo",
    )

    /** Allowed values of list settings: anything else would break Shairport's configuration. */
    fun choices(context: Context): Map<String, List<String>> = mapOf(
        MODEL to context.resources.getStringArray(R.array.model_values).toList(),
        PLAYBACK_MODE to context.resources.getStringArray(R.array.playback_mode_values).toList(),
    )

    /** Human names of [choices], in the same order (what the settings screen shows). */
    fun labels(context: Context): Map<String, List<String>> = mapOf(
        MODEL to context.resources.getStringArray(R.array.model_entries).toList(),
        PLAYBACK_MODE to context.resources.getStringArray(R.array.playback_mode_entries).toList(),
    )

    /** The app's preferences, with defaults stored for unset keys so every reader agrees. */
    fun get(context: Context): SharedPreferences {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val missing = defaults(context).filterKeys { !preferences.contains(it) }
        if (missing.isNotEmpty()) {
            val editor = preferences.edit()
            missing.forEach { (key, value) ->
                if (value is Boolean) editor.putBoolean(key, value) else editor.putString(key, value as String)
            }
            editor.commit()
        }
        return preferences
    }

    /** Android's user-visible device name (Settings > About phone), e.g. "Xiaomi Pocophone F1". */
    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
            else "${Build.MANUFACTURER} ${Build.MODEL}"

    fun isValidPort(value: String) = value.toIntOrNull()?.let { it in 1..65535 } == true
}
