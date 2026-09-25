package com.hkfuertes.shairportap2

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
    private var nqptp: Process? = null
    private var nqptpSharedDirectory: File? = null
    @Volatile private var startGeneration = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireMulticastLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_starting))
        val generation = ++startGeneration
        try {
            val config = writeConfig()
            val sharedDirectory = startNqptp()
            if (sharedDirectory == null) {
                notifyForeground(R.string.notification_nqptp_error)
            } else {
                Thread({
                    if (!waitForNqptp(sharedDirectory)) {
                        if (generation == startGeneration) {
                            notifyForeground(R.string.notification_nqptp_error)
                        }
                    } else if (generation == startGeneration) {
                        val error = NativeBridge.start(config.absolutePath, sharedDirectory.absolutePath)
                        if (error == null) {
                            notifyForeground(R.string.notification_active)
                        } else {
                            Log.e(TAG, "Could not start JNI bridge: $error")
                            notifyForeground(R.string.notification_native_error)
                        }
                    }
                }, "receiver-start").start()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not write Shairport configuration", e)
            notifyForeground(R.string.notification_config_error)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        startGeneration++
        NativeBridge.stop()
        stopNqptp()
        multicastLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startNqptp(): File? {
        nqptp?.takeIf { it.isAlive }?.let { process ->
            nqptpSharedDirectory?.let { return it }
            process.destroy()
        }
        stopNqptp()

        val externalFiles = getExternalFilesDir(null) ?: run {
            Log.e(TAG, "External app storage is unavailable for NQPTP")
            return null
        }
        val sharedDirectory = File(externalFiles, "nqptp-shm")
        if (!sharedDirectory.exists() && !sharedDirectory.mkdirs()) {
            Log.e(TAG, "Could not create NQPTP shared-memory directory")
            return null
        }
        File(sharedDirectory, "nqptp").delete()
        File(sharedDirectory, "nqptp-supervisor.pid").delete()

        val executable = File(applicationInfo.nativeLibraryDir, "libnqptp.so")
        if (!executable.canExecute()) {
            Log.e(TAG, "NQPTP executable is unavailable: $executable")
            return null
        }
        val userId = android.os.Process.myUid() / PER_USER_RANGE
        val rootSharedDirectory = "/data/media/$userId/Android/data/$packageName/files/nqptp-shm"
        val rootPidFile = "$rootSharedDirectory/nqptp-supervisor.pid"
        val appPid = android.os.Process.myPid()
        // ponytail: poll the app PID once a second; replace only if Magisk exposes parent-bound jobs.
        val command = "NQPTP_SHM_DIRECTORY=${shellQuote(rootSharedDirectory)} " +
            "${shellQuote(executable.absolutePath)} -v & child=\$!; " +
            "printf '%s\\n' \"\$\$\" > ${shellQuote(rootPidFile)}; " +
            "cleanup() { kill \"\$child\" 2>/dev/null || true; wait \"\$child\" 2>/dev/null || true; " +
            "rm -f ${shellQuote(rootPidFile)}; }; " +
            "trap 'cleanup; exit 0' INT TERM; " +
            "while kill -0 $appPid 2>/dev/null; do " +
            "kill -0 \"\$child\" 2>/dev/null || { cleanup; exit 1; }; sleep 1; done; cleanup"
        return try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start().also { process ->
                nqptp = process
                nqptpSharedDirectory = sharedDirectory
                Thread({
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { Log.i(TAG, "NQPTP: $it") }
                    }
                }, "nqptp-log").start()
            }
            sharedDirectory
        } catch (error: Exception) {
            Log.e(TAG, "Could not start NQPTP", error)
            null
        }
    }

    private fun waitForNqptp(sharedDirectory: File): Boolean {
        repeat(20) {
            if (File(sharedDirectory, "nqptp").canRead()) return true
            if (nqptp?.isAlive != true) return false
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun stopNqptp() {
        val pid = nqptpSharedDirectory
            ?.let { File(it, "nqptp-supervisor.pid") }
            ?.takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim().toIntOrNull() }.getOrNull() }
        if (pid != null && pid > 1) {
            try {
                ProcessBuilder("su", "-c", "kill $pid").start()
            } catch (error: Exception) {
                Log.w(TAG, "Could not stop NQPTP supervisor", error)
            }
        }
        nqptp?.destroy()
        nqptp = null
        nqptpSharedDirectory = null
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

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
        private const val PER_USER_RANGE = 100000

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
