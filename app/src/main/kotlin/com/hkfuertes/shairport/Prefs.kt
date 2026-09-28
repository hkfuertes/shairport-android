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
    /** AirPlay 2 (multi-room) needs NQPTP, which needs root; off means classic AirPlay. */
    const val AIRPLAY_2 = "airplay_2"
    const val SERVER_NAME = "server_name"
    const val MODEL = "model"
    const val START_AT_BOOT = "start_at_boot"
    const val PLAYBACK_MODE = "playback_mode"
    /** When false, AirPlay volume stays inside Shairport and leaves Android's music stream alone. */
    const val LINK_STREAM_VOLUME = "link_stream_volume"

    /** What classic AirPlay advertises: the model choice only applies to AirPlay 2. */
    const val GENERIC_MODEL = "ShairportSync"

    /** Typed as the settings screen stores them (EditTextPreference keeps Strings). */
    fun defaults(context: Context): Map<String, Any> = mapOf(
        RECEIVER_ENABLED to true,
        AIRPLAY_2 to false,
        SERVER_NAME to deviceName(context),
        MODEL to GENERIC_MODEL, // HomePod models can't be added to the Home app
        START_AT_BOOT to false,
        PLAYBACK_MODE to "stereo",
        LINK_STREAM_VOLUME to true,
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

    /**
     * Shairport's `airplay_device_id` (48 bits, like a MAC): apps can't read the Wi-Fi MAC. Derived
     * from ANDROID_ID (stable per device, user and signing key), so senders keep recognising us.
     */
    fun deviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val bits = androidId?.toULongOrNull(16)?.toLong() ?: androidId.hashCode().toLong()
        return "0x%012XL".format(macLike(bits))
    }

    /** Low 48 bits as a locally administered unicast address (like Android's random MACs). */
    fun macLike(bits: Long): Long = (bits and 0xFFFF_FFFF_FFFFL or (0x02L shl 40)) and (0x01L shl 40).inv()
}
