package com.hkfuertes.shairport

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The receiver: a foreground service in the app process that runs Shairport Sync in the :engine
 * process (EngineService, bound while it runs) and, with AirPlay 2 on, NQPTP through su.
 */
class ReceiverService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var volumeSync: VolumeSync
    @Volatile private var engine: ServiceConnection? = null
    private var engineConfig: String? = null
    private var engineSatellites = false
    /** Root watcher running NQPTP; closing its stdin stops it. */
    private var nqptp: Process? = null
    /** Worker tasks queued before onDestroy must not start an engine afterwards. */
    @Volatile private var destroyed = false

    private val engineMessages = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            EngineService.MSG_ADVERTISED -> EngineStatus.advertised(message.arg1 != 0, message.arg2 != 0)
            EngineService.MSG_SATELLITES -> EngineStatus.satellites(
                message.data.getStringArrayList(EngineService.KEY_ADDRESSES).orEmpty(),
                message.data.getStringArrayList(EngineService.KEY_HELLOS).orEmpty(),
            )
        }
        true
    })

    /** NsdManager follows address changes by itself; the status shows the current address. */
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val ipv4 = properties.linkAddresses.firstOrNull { it.address is Inet4Address }
                ?.address?.hostAddress ?: return
            if (ipv4 == EngineStatus.address) return
            // After a reconnect the POCO filters multicast again although the lock is held;
            // re-acquiring it re-applies it (otherwise mDNS queries never arrive).
            multicastLock?.run {
                release()
                acquire()
            }
            EngineStatus.wifiAddress(ipv4)
        }

        override fun onLost(network: Network) = EngineStatus.wifiAddress(null)
    }

    /** Applies setting changes from any source (settings screen or adb's SettingsReceiver). */
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
        if (key == Prefs.RECEIVER_ENABLED && !preferences.getBoolean(Prefs.RECEIVER_ENABLED, true)) stopSelf()
        else onWorker { startEngine() } // no-op unless the resulting configuration changed
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(preferenceListener)
        createNotificationChannel()
        acquireWifiLocks()
        volumeSync = VolumeSync(this).also { it.start() }
        EngineStatus.wifiAddress(wifiAddress())
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiCallback,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { // the notification's "Stop"
            Prefs.get(this).edit().putBoolean(Prefs.RECEIVER_ENABLED, false).commit()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)))
        onWorker { startEngine() }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        // Unbind now (a destroyed service can't later); the waiting goes to the worker.
        destroyed = true
        engine?.let { runCatching { unbindService(it) } }
        engine = null
        val watcher = nqptp
        onWorker {
            EngineStatus.engineStopped()
            awaitEngineExit()
            stopNqptp(watcher)
        }
        volumeSync.stop()
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** (Re)starts the engine unless it is already running with the same configuration. */
    private fun startEngine() {
        if (destroyed) return
        val preferences = Prefs.get(this)
        val name = value(preferences, Prefs.SERVER_NAME)
        val airplay2 = preferences.getBoolean(Prefs.AIRPLAY_2, false)
        val config = config(preferences, name, airplay2)
        val satellites = preferences.getBoolean(Prefs.SATELLITES, false)
        if (engine != null && config == engineConfig && satellites == engineSatellites) {
            notifyForeground(getString(R.string.notification_active, name))
            return
        }
        stopEngine()
        awaitEngineExit() // also one left by a previous instance of this service
        val configFile = File(filesDir, CONFIG_FILE)
        val shm = getExternalFilesDir(null)?.let { File(it, SHM_DIRECTORY) }
        try {
            configFile.writeText(config)
            shm?.mkdirs()
        } catch (error: Exception) {
            Log.e(TAG, "Could not write Shairport configuration", error)
            notifyForeground(getString(R.string.notification_config_error))
            return
        }
        // Shairport looks for NQPTP once, when it starts ("auto": classic AirPlay without it).
        if (airplay2 && shm != null) nqptp = startNqptp()

        val startedAt = SystemClock.elapsedRealtime()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = EngineStatus.shairportRunning(true)
            override fun onServiceDisconnected(name: ComponentName) = onWorker { engineDied(this, startedAt) }
        }
        val intent = Intent(this, EngineService::class.java)
            .putExtra(EngineService.EXTRA_ARGUMENTS, arrayOf("shairport-sync", "-c", configFile.path))
            .putExtra(EngineService.EXTRA_SHM_DIRECTORY, shm?.path.orEmpty())
            .putExtra(EngineService.EXTRA_STATUS, engineMessages)
            .putExtra(EngineService.EXTRA_SATELLITES, satellites)
            // A change restarts the engine anyway: it changes ignore_volume_control.
            .putExtra(EngineService.EXTRA_LINKED_VOLUME, Prefs.linkedVolume(preferences))
        if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "Could not start the engine process")
            notifyForeground(getString(R.string.notification_engine_error))
            return
        }
        engine = connection
        engineConfig = config
        engineSatellites = satellites
        notifyForeground(getString(R.string.notification_active, name))
    }

    /** The :engine process ended without being asked to (Shairport exited or crashed). */
    private fun engineDied(connection: ServiceConnection, startedAt: Long) {
        if (engine !== connection) return
        // ponytail: restart only engines that ran a while, so a broken setup can't spin.
        val restart = SystemClock.elapsedRealtime() - startedAt > MIN_UPTIME_FOR_RESTART_MS
        Log.w(TAG, "Shairport Sync exited" + if (restart) "; restarting" else "")
        stopEngine()
        if (restart) {
            Thread.sleep(RESTART_DELAY_MS)
            startEngine()
        } else {
            notifyForeground(getString(R.string.notification_engine_error))
        }
    }

    private fun stopEngine() {
        engine?.let { runCatching { unbindService(it) } } // EngineService.onDestroy stops Shairport
        engine = null
        engineConfig = null
        EngineStatus.engineStopped()
        awaitEngineExit()
        stopNqptp(nqptp)
        nqptp = null
    }

    /** Shairport exits in well under a second; a new engine must not start inside the old process. */
    private fun awaitEngineExit() {
        val pid = enginePid() ?: return
        repeat(ENGINE_EXIT_POLLS) {
            Thread.sleep(ENGINE_EXIT_POLL_MS)
            if (enginePid() == null) return
        }
        Log.w(TAG, "Shairport Sync did not exit in time; killing its process")
        android.os.Process.killProcess(pid)
        repeat(ENGINE_EXIT_POLLS) {
            if (enginePid() == null) return
            Thread.sleep(ENGINE_EXIT_POLL_MS)
        }
    }

    private fun enginePid(): Int? = getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == "$packageName:engine" }?.pid

    /**
     * AirPlay 2 timing: NQPTP binds UDP 319/320, which needs root. Starts the root watcher and
     * waits for NQPTP's shared memory (or su's refusal); Magisk may be showing its prompt.
     */
    private fun startNqptp(): Process? {
        val process = try {
            ProcessBuilder("su", "-c", nqptpScript()).redirectErrorStream(true).start()
        } catch (error: IOException) {
            Log.w(TAG, "No su: AirPlay 2 needs root; starting classic AirPlay", error)
            return null
        }
        val ready = CountDownLatch(1)
        Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    when (line) {
                        "$MARKER up" -> EngineStatus.nqptpRunning(true).also { ready.countDown() }
                        "$MARKER down" -> EngineStatus.nqptpRunning(false).also { ready.countDown() }
                        else -> Log.i(TAG, line)
                    }
                }
            }
            ready.countDown() // su refused or ended
        }, "nqptp-log").start()
        if (!ready.await(NQPTP_START_TIMEOUT_S, TimeUnit.SECONDS)) Log.w(TAG, "NQPTP not ready in time")
        return process
    }

    private fun stopNqptp(process: Process?) {
        process ?: return
        runCatching { process.outputStream.close() } // EOF: the watcher stops NQPTP
        if (!process.waitFor(NQPTP_STOP_TIMEOUT_S, TimeUnit.SECONDS)) process.destroy()
        EngineStatus.nqptpRunning(false)
    }

    /**
     * Runs as root until its stdin reaches EOF: the app stopped AirPlay 2, or died. NQPTP writes
     * its shared memory where Magisk root may write and the app may read: external app storage.
     */
    private fun nqptpScript(): String {
        val userId = android.os.Process.myUid() / PER_USER_RANGE
        val shm = shellQuote("/data/media/$userId/Android/data/$packageName/files/$SHM_DIRECTORY")
        val binary = shellQuote("${applicationInfo.nativeLibraryDir}/libnqptp.so")
        return """
            trap '' PIPE # the app (our stdout reader) may already be dead
            exec 4<&0 0</dev/null
            # One NQPTP at a time: the previous one may still be shutting down (app restart).
            old=${'$'}(pidof libnqptp.so)
            if [ -n "${'$'}old" ]; then kill ${'$'}old; sleep 0.5; kill -9 ${'$'}old; fi 2>/dev/null
            # Screen off = Wi-Fi power save: the POCO stops answering ARP/TCP (mDNS still works)
            # and satellites starve. App Wi-Fi locks can't prevent it on API 34+, nor can
            # force-hi-perf-mode once the device dozes; low-latency mode does, until NQPTP stops.
            cmd wifi force-low-latency-mode enabled >/dev/null 2>&1
            export NQPTP_SHM_DIRECTORY=$shm
            rm -f $shm/nqptp
            $binary 4<&- &
            nqptp=${'$'}!
            i=0
            while [ ! -s $shm/nqptp ] && [ ${'$'}i -lt 50 ]; do sleep 0.1; i=${'$'}((i + 1)); done
            if [ -s $shm/nqptp ]; then echo "$MARKER up"; else echo "$MARKER down"; fi
            read -r _ <&4
            kill ${'$'}nqptp 2>/dev/null
            sleep 1
            kill -9 ${'$'}nqptp 2>/dev/null
            cmd wifi force-low-latency-mode disabled >/dev/null 2>&1
            echo "$MARKER down"
        """.trimIndent()
    }

    // No port: Shairport always uses 7000 for AirPlay 2 and 5000 for classic AirPlay.
    private fun config(preferences: SharedPreferences, name: String, airplay2: Boolean): String = """
        general = {
          name = ${quote(name)};
          model = ${quote(if (airplay2) value(preferences, Prefs.MODEL) else Prefs.GENERIC_MODEL)};
          playback_mode = ${quote(value(preferences, Prefs.PLAYBACK_MODE))};
          output_backend = "aaudio";
          service_type = ${if (airplay2) "\"auto\"" else "\"classic\""}; // auto: classic without NQPTP
          airplay_device_id = ${Prefs.deviceId(this)}; // the app has no MAC address to use
          ignore_volume_control = ${quote(if (Prefs.linkedVolume(preferences)) "yes" else "no")}; // linked: VolumeSync maps it onto STREAM_MUSIC
          volume_range_db = 40; // when Shairport sets the volume: its default 96 dB leaves half the slider near silent
          // Satellites get the audio this far ahead (the AAudio buffer): room for Wi-Fi hiccups.
          audio_backend_buffer_desired_length_in_seconds = ${if (preferences.getBoolean(Prefs.SATELLITES, false)) "1.0" else "0.5"};
        };
        metadata = {
          enabled = "yes";
          include_cover_art = "no";
          socket_address = "127.0.0.1";
          socket_port = ${volumeSync.port};
        };
        diagnostics = {
          statistics = "yes"; // ponytail: sync stats in logcat until AAudio is proven
        };
    """.trimIndent() + "\n"

    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
        return "\"$escaped\""
    }

    /** A text setting; blank (e.g. a cleared name) falls back to its default. */
    private fun value(preferences: SharedPreferences, key: String): String =
        preferences.getString(key, null).takeUnless { it.isNullOrBlank() }
            ?: Prefs.defaults(this)[key] as String

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    @Suppress("DEPRECATION") // allNetworks: simplest way to read the current Wi-Fi address once
    private fun wifiAddress(): String? {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstNotNullOfOrNull { network ->
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
            else connectivity.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        }
    }

    /**
     * Multicast for mDNS. The high-performance lock keeps the receiver reachable with the screen
     * off up to Android 13; from 14 on it only works with the screen on (AirPlay 2's root
     * watcher forces hi-perf mode instead).
     */
    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: the lock that still works before API 34
    private fun acquireWifiLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock(TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
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
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_airplay_audio),
                    getString(R.string.notification_stop),
                    stop,
                ).build(),
            )
            .setSmallIcon(R.drawable.ic_airplay_audio) // alpha-only glyph, as status icons need
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "Shairport"
        private const val MARKER = "@nqptp"
        private const val ACTION_STOP = "com.hkfuertes.shairport.STOP"
        private const val CHANNEL_ID = "shairport_receiver"
        private const val NOTIFICATION_ID = 1
        private const val PER_USER_RANGE = 100000
        private const val CONFIG_FILE = "shairport-sync.conf"
        private const val SHM_DIRECTORY = "nqptp-shm"
        private const val MIN_UPTIME_FOR_RESTART_MS = 30_000L
        private const val RESTART_DELAY_MS = 2_000L
        private const val ENGINE_EXIT_POLLS = 50
        private const val ENGINE_EXIT_POLL_MS = 100L
        /** Covers Magisk's grant prompt (10 s by default). */
        private const val NQPTP_START_TIMEOUT_S = 15L
        private const val NQPTP_STOP_TIMEOUT_S = 3L

        /** One worker for every instance: a new service's start waits for the old one's stop. */
        private val worker = Executors.newSingleThreadExecutor()

        private fun onWorker(task: () -> Unit) {
            worker.execute(task)
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
