package com.hkfuertes.shairport

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes the app settings through explicit adb broadcasts (see README). Protected by
 * android.permission.DUMP in the manifest, so regular apps cannot use it. A running receiver
 * service applies changes itself (it listens to the preferences).
 */
class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = Prefs.get(context)
        when (intent.action) {
            ACTION_CONFIGURE -> {
                val result = configure(context, intent, preferences)
                publish(result, "Updated")
                if (result.getOrNull() == Prefs.START_AT_BOOT) { // installing the script needs su
                    val pending = goAsync()
                    Thread({ BootScript.sync(context); pending.finish() }, "boot-script").start()
                }
            }
            ACTION_LIST -> publish(list(context, preferences), "Settings")
        }
    }

    private fun publish(result: Result<String>, successLabel: String) {
        if (result.isSuccess) {
            val data = result.getOrThrow()
            setResultCode(Activity.RESULT_OK)
            setResultData(data)
            Log.i(TAG, "$successLabel: $data")
        } else {
            setResultCode(Activity.RESULT_CANCELED)
            setResultData("Settings rejected: ${result.exceptionOrNull()?.message}")
            Log.w(TAG, "Settings rejected", result.exceptionOrNull())
        }
    }

    @Suppress("DEPRECATION") // Bundle.get: the value's type is what we validate
    private fun configure(context: Context, intent: Intent, preferences: SharedPreferences): Result<String> =
        runCatching {
            val extras = requireNotNull(intent.extras) { "key and value are required" }
            require(extras.keySet() == setOf(EXTRA_KEY, EXTRA_VALUE)) { "only key and value are allowed" }
            val key = intent.getStringExtra(EXTRA_KEY) ?: error("key must be text (--es)")
            val default = Prefs.defaults(context)[key] ?: error("unknown setting: $key")
            val value = extras.get(EXTRA_VALUE) ?: error("value is required")
            require(value::class == default::class) {
                "$key expects ${typeName(default)}; got ${typeName(value)}"
            }
            if (key == Prefs.PORT) require(Prefs.isValidPort(value as String)) { "port must be 1-65535" }
            // List settings take the value or its human label ("HomePod mini" = AudioAccessory5,1).
            val stored: Any = Prefs.choices(context)[key]?.let { allowed ->
                val labels = Prefs.labels(context).getValue(key)
                val index = allowed.indexOf(value).takeIf { it >= 0 }
                    ?: labels.indexOfFirst { it.equals(value as String, ignoreCase = true) }
                require(index >= 0) { "$key must be one of $allowed or $labels" }
                allowed[index]
            } ?: value
            val editor = preferences.edit()
            if (stored is Boolean) editor.putBoolean(key, stored) else editor.putString(key, stored as String)
            check(editor.commit()) { "failed to persist settings" }
            key
        }

    private fun list(context: Context, preferences: SharedPreferences): Result<String> = runCatching {
        val settings = JSONArray()
        Prefs.defaults(context).toSortedMap().forEach { (key, default) ->
            settings.put(JSONObject().apply {
                put("key", key)
                put("type", typeName(default))
                put("value", preferences.all[key] ?: default)
                put("default", default)
                Prefs.choices(context)[key]?.let { put("choices", JSONArray(it)) }
                Prefs.labels(context)[key]?.let { put("labels", JSONArray(it)) }
            })
        }
        JSONObject().put("settings", settings).toString()
    }

    private fun typeName(value: Any): String = when (value) {
        is Boolean -> "boolean (--ez)"
        is String -> "string (--es)"
        else -> value::class.java.simpleName
    }

    companion object {
        const val ACTION_CONFIGURE = "com.hkfuertes.shairport.CONFIGURE_SETTINGS"
        const val ACTION_LIST = "com.hkfuertes.shairport.LIST_SETTINGS"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
        private const val TAG = "Shairport"
    }
}
