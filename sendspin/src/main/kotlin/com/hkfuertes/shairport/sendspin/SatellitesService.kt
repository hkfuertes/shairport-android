package com.hkfuertes.shairport.sendspin

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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Sendspin satellites for Shairport: the Sendspin server ([Satellites]) fed by Shairport's audio
 * tap ([Tap]), in a foreground service, so its sockets keep the network with the screen off.
 * Started is on ([ENABLED], also at boot); [ENABLED] off stops it. Settings apply at once.
 */
class SatellitesService : Service() {
    private lateinit var preferences: SharedPreferences
    private lateinit var nsd: NsdManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var satellites: Satellites? = null
    private var discovery: PlayerDiscovery? = null
    private var shairport: Messenger? = null
    /** Our end of the tap's socket; the reader of an older one ends quietly. */
    @Volatile private var socket: ParcelFileDescriptor? = null

    private val advertised = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "Advertising \"${info.serviceName}\" ($SERVER_TYPE)")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Could not advertise \"${info.serviceName}\": error $errorCode")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            shairport = Messenger(service)
            open()
        }

        // Shairport's process ended: Android binds again once it's back.
        override fun onServiceDisconnected(name: ComponentName) {
            shairport = null
            linked(Link.WAITING)
        }

        // Shairport was updated or reinstalled: this binding is over.
        override fun onBindingDied(name: ComponentName) {
            unbind()
            bind()
        }
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
        when (key) {
            ENABLED -> if (!preferences.getBoolean(ENABLED, false)) stopSelf()
            SILENT, ONLY_44100 -> open()
        }
    }

    /** The notification follows the status (from any thread; never after onDestroy). */
    private val notify: () -> Unit = {
        main.post {
            if (satellites != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = preferences(this)
        nsd = getSystemService(NsdManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification())
        val name = deviceName()
        val server = runCatching { Satellites(name, identity(), log = { Log.i(TAG, it) }, changed = { report(it) }) }
            .onFailure { Log.e(TAG, "Could not listen on port ${Satellites.PORT}", it) }
            .getOrNull()
        if (server == null) return stopSelf()
        satellites = server
        listeners += notify
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVER_TYPE
            port = Satellites.PORT
            runCatching {
                setAttribute("path", Satellites.PATH)
                setAttribute("name", name)
            }
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, advertised)
        discovery = PlayerDiscovery(nsd, server).also { nsd.discoverServices(PLAYER_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        bind()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started is on: the switch shows it, and it starts again at boot.
        if (satellites != null) preferences.edit().putBoolean(ENABLED, true).apply()
        return START_STICKY
    }

    override fun onDestroy() {
        listeners -= notify
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        unbind()
        socket?.let { // our end: Shairport's tap ends, and with it any silence there
            socket = null
            it.close()
        }
        discovery?.let {
            it.stop()
            runCatching { nsd.stopServiceDiscovery(it) }
        }
        runCatching { nsd.unregisterService(advertised) }
        satellites?.close()
        satellites = null
        report(emptyList())
        linked(Link.OFF)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bind() {
        val tap = Intent(Tap.ACTION).setPackage(Tap.PACKAGE)
        // No Shairport, or one signed with another key (its tap's permission): nothing to tap.
        val bound = runCatching { bindService(tap, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) Log.w(TAG, "No Shairport audio tap: Shairport not installed, or signed with another key")
        // ponytail: a Shairport installed later is found when the satellites start again.
        linked(if (bound) Link.WAITING else Link.MISSING)
    }

    private fun unbind() {
        runCatching { unbindService(connection) }
        shairport = null
    }

    /** (Re)opens Shairport's tap with the current settings, on a new socket. */
    private fun open() {
        val shairport = shairport ?: return
        val (ours, theirs) = seqpacketPair()
        val data = Bundle().apply {
            putParcelable(Tap.KEY_SOCKET, theirs)
            putFloat(Tap.KEY_LEAD, Tap.LEAD_SECONDS)
            putBoolean(Tap.KEY_SILENT, preferences.getBoolean(SILENT, false))
            putBoolean(Tap.KEY_44100, preferences.getBoolean(ONLY_44100, false))
        }
        val sent = runCatching { shairport.send(Message.obtain(null, Tap.MSG_OPEN).apply { this.data = data }) }
        theirs.close() // Shairport has its own copy
        if (sent.isFailure) return ours.close()
        // The old socket ends when Shairport lets go of it: closing it here would end the tap first.
        socket = ours
        read(ours)
        linked(Link.CONNECTED)
    }

    /** Shairport's messages to the satellites, until Shairport closes [socket]. */
    private fun read(socket: ParcelFileDescriptor) = thread(name = "tap") {
        val message = ByteArray(Tap.MAX_MESSAGE)
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(socket).use { input ->
                while (true) {
                    val length = input.read(message) // one message per read
                    if (length <= 0) break
                    satellites?.let { Tap.deliver(message, length, it) }
                }
            }
        }
        // Shairport ended the tap (it stopped, say): the players stop too, unless a newer tap goes on.
        if (this.socket === socket) satellites?.audio(ByteArray(0), 0, 0)
    }

    /** The tap's socket: SOCK_SEQPACKET, so messages keep their bounds (ParcelFileDescriptor's are streams). */
    private fun seqpacketPair(): List<ParcelFileDescriptor> {
        val ends = listOf(FileDescriptor(), FileDescriptor())
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, ends[0], ends[1])
        return ends.map { ParcelFileDescriptor.dup(it).also { _ -> Os.close(it) } }
    }

    /** Our Sendspin server_id's private key: made once, so players keep recognising us. */
    private fun identity(): ByteArray {
        val file = File(filesDir, IDENTITY_FILE)
        runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size == 32 }?.let { return it }
        return Noise.privateKey().also { key ->
            runCatching { file.writeBytes(key) }.onFailure { Log.w(TAG, "Identity not saved", it) }
        }
    }

    /** Android's device name (Settings > About phone), as Shairport's default name. */
    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_satellites)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(summary(this))
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .setShowWhen(false)
        .build()

    /**
     * Players that wait for a server (`_sendspin._tcp`): resolved one at a time (older NsdManagers
     * refuse concurrent resolves), then [Satellites.connect]ed while advertised.
     */
    private class PlayerDiscovery(private val nsd: NsdManager, private val satellites: Satellites) : NsdManager.DiscoveryListener {
        private val resolver = Executors.newSingleThreadExecutor()

        fun stop() {
            resolver.shutdownNow()
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            runCatching { resolver.execute { resolve(info) } } // none once stopped
        }

        private fun resolve(info: NsdServiceInfo) {
            val done = CountDownLatch(1)
            @Suppress("DEPRECATION") // resolveService: the replacement is API 34+
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host
                    // ponytail: IPv4 only; a link-local IPv6 address needs its scope in the URI.
                    if (host is Inet4Address) {
                        val path = resolved.attributes["path"]?.let { String(it, Charsets.UTF_8) } ?: Satellites.PATH
                        Log.i(TAG, "Found player \"${resolved.serviceName}\" at ${host.hostAddress}:${resolved.port}")
                        satellites.connect(resolved.serviceName, URI("ws", null, host.hostAddress, resolved.port, path, null, null))
                    }
                    done.countDown()
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Could not resolve \"${failed.serviceName}\": error $errorCode")
                    done.countDown()
                }
            })
            runCatching { done.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }

        override fun onServiceLost(info: NsdServiceInfo) = satellites.forget(info.serviceName)
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Could not look for players: error $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    /** How the satellites reach Shairport's audio. */
    enum class Link { OFF, WAITING, CONNECTED, MISSING }

    /** [name]: the player's name; [client]: its product and software version ("Linux 8.0.0"). */
    class Player(val name: String, val address: String, val client: String)

    companion object {
        const val ENABLED = "enabled"
        /** Only the satellites play: Shairport's device stays silent. */
        const val SILENT = "silent"
        /** AirPlay 2 at 44.1 kHz only (no lossless), for players that can't play 48 kHz. */
        const val ONLY_44100 = "only_44100"
        /** Every setting: booleans, off by default. */
        val SETTINGS = listOf(ENABLED, SILENT, ONLY_44100)
        private const val SERVER_TYPE = "_sendspin-server._tcp"
        private const val PLAYER_TYPE = "_sendspin._tcp"
        private const val IDENTITY_FILE = "sendspin_identity"
        private const val CHANNEL_ID = "satellites"
        private const val NOTIFICATION_ID = 1
        private const val RESOLVE_TIMEOUT_MS = 10_000L
        private const val TAG = "Satellites"

        @Volatile var link = Link.OFF
            private set
        @Volatile var players: List<Player> = emptyList()
            private set
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun addListener(listener: () -> Unit) = listeners.add(listener)
        fun removeListener(listener: () -> Unit) = listeners.remove(listener)

        private fun linked(link: Link) {
            this.link = link
            listeners.forEach { it() }
        }

        /** Each player's address and client/hello: JSON from a player of any make, every field optional. */
        private fun report(connected: List<Pair<String, String>>) {
            players = connected.map { (address, hello) ->
                val json = runCatching { JSONObject(hello) }.getOrElse { JSONObject() }
                val device = json.optJSONObject("device_info") ?: JSONObject()
                val client = listOf(device.optString("product_name"), device.optString("software_version")).filter { it.isNotBlank() }
                Player(json.optString("name").ifBlank { address }, address, client.joinToString(" "))
            }
            listeners.forEach { it() }
        }

        /** The state in a few words: the notification's and the screen's. */
        fun summary(context: Context): String = when (link) {
            Link.OFF -> context.getString(R.string.link_off)
            Link.WAITING -> context.getString(R.string.link_waiting)
            Link.MISSING -> context.getString(R.string.link_missing)
            Link.CONNECTED -> context.resources.getQuantityString(R.plurals.link_connected, players.size, players.size)
        }

        @Suppress("DEPRECATION") // framework PreferenceManager, like the classic settings screen
        fun preferences(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SatellitesService::class.java))
        }
    }
}
