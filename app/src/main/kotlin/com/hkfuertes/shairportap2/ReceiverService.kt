package com.hkuertes.shairportap2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets

class ReceiverService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireMulticastLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_active))
        try {
            val error = NativeBridge.start(writeConfig().absolutePath)
            if (error == null) {
                notifyForeground(R.string.notification_active)
            } else {
                Log.e(TAG, "Could not start JNI bridge: $error")
                notifyForeground(R.string.notification_native_error)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not write Shairport configuration", e)
            notifyForeground(R.string.notification_config_error)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        NativeBridge.stop()
        multicastLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock(TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun notifyForeground(text: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(text))
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun writeConfig(): File {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val config = File(filesDir, "shairport-sync.conf")
        OutputStreamWriter(FileOutputStream(config), StandardCharsets.UTF_8).use { writer ->
            writer.write("general = {\n")
            writeQuoted(writer, "name", value(preferences, MainActivity.PREF_SERVER_NAME, "Shairport AP2 Android"))
            writeQuoted(writer, "model", value(preferences, MainActivity.PREF_MODEL, "AudioAccessory1,1"))
            writeQuoted(writer, "service_type", "airplay2")
            writeQuoted(writer, "output_backend", "audiotrack")
            writeQuoted(writer, "mdns_backend", "tinysvcmdns")
            writeQuoted(writer, "interface", value(preferences, MainActivity.PREF_NETWORK_INTERFACE, "wlan0"))
            writer.write("  port = ${port(value(preferences, MainActivity.PREF_PORT, "7000"))};\n")
            writeQuoted(writer, "playback_mode", value(preferences, MainActivity.PREF_PLAYBACK_MODE, "stereo"))
            writer.write("};\n")
        }
        return config
    }

    private fun writeQuoted(writer: Writer, key: String, value: String) {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
        writer.write("  $key = \"$escaped\";\n")
    }

    private fun value(
        preferences: android.content.SharedPreferences,
        key: String,
        fallback: String,
    ): String = preferences.getString(key, fallback).takeUnless { it.isNullOrBlank() } ?: fallback

    private fun port(value: String) = if (MainActivity.isValidPort(value)) value.toInt() else 7000

    companion object {
        private const val TAG = "ShairportAP2"
        private const val CHANNEL_ID = "shairport_receiver"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
