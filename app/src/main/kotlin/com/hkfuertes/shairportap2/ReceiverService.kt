package com.hkfuertes.shairportap2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ReceiverService : Service() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var volumeSync: VolumeSync
    @Volatile private var engine: Process? = null
    private var engineConfig: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireMulticastLock()
        volumeSync = VolumeSync(this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)))
        worker.execute { startEngine() }
        return START_STICKY
    }

    override fun onDestroy() {
        worker.execute {
            stopEngine()
            // ponytail: after a crash/force-stop this stays on until the next normal stop.
            runCatching { ProcessBuilder("su", "-c", "cmd wifi force-hi-perf-mode disabled").start() }
        }
        worker.shutdown()
        volumeSync.stop()
        multicastLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** (Re)starts the engine unless it is already running with the same configuration. */
    private fun startEngine() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val name = value(preferences, MainActivity.PREF_SERVER_NAME, DEFAULT_NAME)
        val config = config(preferences, name)
        if (engine != null && config == engineConfig) {
            notifyForeground(getString(R.string.notification_active, name))
            return
        }
        stopEngine()
        val files = getExternalFilesDir(null)
        if (files == null) {
            notifyForeground(getString(R.string.notification_storage_error))
            return
        }
        try {
            File(files, SHM_DIRECTORY).mkdirs()
            File(files, CONFIG_FILE).writeText(config)
        } catch (error: Exception) {
            Log.e(TAG, "Could not write Shairport configuration", error)
            notifyForeground(getString(R.string.notification_config_error))
            return
        }

        val process = try {
            ProcessBuilder("su", "-c", supervisorScript()).start()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start the root engine", error)
            notifyForeground(getString(R.string.notification_engine_error))
            return
        }
        engine = process
        engineConfig = config
        Thread({
            process.errorStream.bufferedReader().forEachLine { Log.i(TAG, it) }
        }, "engine-log").start()
        Thread({
            try {
                play(process.inputStream)
            } catch (error: Exception) {
                Log.e(TAG, "Audio output failed", error)
            }
            if (engine === process) {
                Log.w(TAG, "Shairport Sync exited")
                notifyForeground(getString(R.string.notification_engine_error))
                worker.execute { if (engine === process) stopEngine() }
            }
        }, "engine-audio").start()
        notifyForeground(getString(R.string.notification_active, name))
    }

    /**
     * Runs as root; lives exactly as long as Shairport. A watcher stops Shairport when the
     * app closes our stdin (normal stop, or the app process dying). The app only sees EOF on
     * the audio pipe once this whole script exits, because Magisk's su client keeps its own
     * copy of stdout open until then.
     */
    private fun supervisorScript(): String {
        val userId = android.os.Process.myUid() / PER_USER_RANGE
        val rootFiles = "/data/media/$userId/Android/data/$packageName/files"
        val libraries = applicationInfo.nativeLibraryDir
        val shm = shellQuote("$rootFiles/$SHM_DIRECTORY")
        return """
            trap '' PIPE # the app (our stderr reader) may already be dead
            exec 3>&1 1>&2 4<&0 0</dev/null
            # One engine at a time: the previous one may still be shutting down (app restart).
            old=${'$'}(pidof libshairport_sync.so libnqptp.so)
            if [ -n "${'$'}old" ]; then
              kill ${'$'}old 2>/dev/null
              i=0
              while kill -0 ${'$'}old 2>/dev/null && [ ${'$'}i -lt 30 ]; do sleep 0.1; i=${'$'}((i + 1)); done
              kill -9 ${'$'}old 2>/dev/null
            fi
            # Screen off = Wi-Fi power save: the POCO stops answering ARP/TCP (mDNS still
            # works), so senders see us but can't connect. App Wi-Fi locks can't prevent it
            # on API 34+; root can. Turned off again when the service stops normally.
            cmd wifi force-hi-perf-mode enabled >/dev/null 2>&1
            export NQPTP_SHM_DIRECTORY=$shm
            rm -f $shm/nqptp
            ${shellQuote("$libraries/libnqptp.so")} 3>&- 4<&- & nqptp=${'$'}!
            i=0
            while [ ! -s $shm/nqptp ] && [ ${'$'}i -lt 50 ]; do sleep 0.1; i=${'$'}((i + 1)); done
            ${shellQuote("$libraries/libshairport_sync.so")} -c ${shellQuote("$rootFiles/$CONFIG_FILE")} 1>&3 3>&- 4<&- &
            shairport=${'$'}!
            exec 3>&-
            # Bionic can't cancel threads blocked in recvfrom(), so a live session can hang
            # Shairport's exit (pthread_join chain): SIGKILL it if SIGTERM didn't work in 3 s.
            { read -r _ <&4; kill ${'$'}shairport; sleep 3; kill -9 ${'$'}shairport; } 2>/dev/null &
            watcher=${'$'}!
            exec 4<&-
            wait ${'$'}shairport
            status=${'$'}?
            kill ${'$'}watcher ${'$'}nqptp 2>/dev/null
            echo "shairport-sync exited: ${'$'}status"
            sleep 1
            kill -9 ${'$'}nqptp 2>/dev/null
            wait
        """.trimIndent()
    }

    private fun stopEngine() {
        val process = engine ?: return
        engine = null
        runCatching { process.outputStream.close() }
        repeat(50) {
            if (process.exited()) return
            Thread.sleep(100)
        }
        Log.w(TAG, "Root engine did not stop in time")
        process.destroy()
    }

    // ponytail: plain blocking PCM pump; Shairport paces output, no sync feedback from AudioTrack.
    private fun play(input: InputStream) {
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE * BYTES_PER_FRAME / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val buffer = ByteArray(8192)
            var pending = 0
            while (true) {
                val read = input.read(buffer, pending, buffer.size - pending)
                if (read < 0) break
                pending += read
                val whole = pending - pending % BYTES_PER_FRAME
                if (whole > 0) {
                    track.write(buffer, 0, whole)
                    buffer.copyInto(buffer, 0, whole, pending)
                    pending -= whole
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun config(preferences: SharedPreferences, name: String): String = """
        general = {
          name = ${quote(name)};
          model = ${quote(value(preferences, MainActivity.PREF_MODEL, "AudioAccessory1,1"))};
          interface = ${quote(value(preferences, MainActivity.PREF_NETWORK_INTERFACE, "wlan0"))};
          port = ${port(value(preferences, MainActivity.PREF_PORT, "7000"))};
          playback_mode = ${quote(value(preferences, MainActivity.PREF_PLAYBACK_MODE, "stereo"))};
          output_backend = "stdout";
          mdns_backend = "tinysvcmdns";
          ignore_volume_control = "yes"; // VolumeSync maps it onto STREAM_MUSIC instead
        };
        metadata = {
          enabled = "yes";
          include_cover_art = "no";
          socket_address = "127.0.0.1";
          socket_port = ${volumeSync.port};
        };
        stdout = {
          output_format = "S16_LE";
          output_rate = $SAMPLE_RATE;
          output_channels = 2;
        };
    """.trimIndent() + "\n"

    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
        return "\"$escaped\""
    }

    private fun value(preferences: SharedPreferences, key: String, fallback: String): String =
        preferences.getString(key, fallback).takeUnless { it.isNullOrBlank() } ?: fallback

    private fun port(value: String) = if (MainActivity.isValidPort(value)) value.toInt() else 7000

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun Process.exited(): Boolean = try {
        exitValue()
        true
    } catch (_: IllegalThreadStateException) {
        false
    }

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

    private fun notifyForeground(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
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
            .setContentText(text)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "ShairportAP2"
        private const val CHANNEL_ID = "shairport_receiver"
        private const val NOTIFICATION_ID = 1
        private const val PER_USER_RANGE = 100000
        private const val SAMPLE_RATE = 44100
        private const val BYTES_PER_FRAME = 4 // S16_LE stereo
        private const val CONFIG_FILE = "shairport-sync.conf"
        private const val SHM_DIRECTORY = "nqptp-shm"
        private const val DEFAULT_NAME = "Shairport AP2 Android"


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
