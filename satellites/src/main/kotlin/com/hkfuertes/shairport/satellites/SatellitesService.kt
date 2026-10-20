package com.hkfuertes.shairport.satellites

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
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Satellites for Shairport: this app's [Protocol] (Sendspin or Snapcast, its build flavor), whose
 * server is fed by Shairport's audio tap ([Tap]), in a foreground service, so its sockets keep the
 * network with the screen off. Started is on ([ENABLED], also at boot); [ENABLED] off stops it.
 * Settings apply at once.
 */
class SatellitesService : Service() {
    private lateinit var preferences: SharedPreferences
    /**
     * mDNS hears the network: without it some Wi-Fi drivers (the POCO F1's) drop the multicast that
     * answers our player searches and asks for our server, unless Shairport's receiver holds one.
     */
    // ponytail: taken once; Shairport re-takes its own after a Wi-Fi reconnect (some drivers forget
    // it then), do the same here if players go missing after one with Shairport's receiver off.
    private var multicast: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var protocol: Protocol? = null
    @Volatile private var shairport: Messenger? = null
    /** Our end of the tap's socket; the reader of an older one ends quietly. */
    @Volatile private var socket: ParcelFileDescriptor? = null

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
            if (protocol != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = preferences(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification())
        protocol = runCatching { Protocol(this, log = { Log.i(TAG, it) }, changed = { report(it) }) }
            .onFailure { Log.e(TAG, "Could not listen on port ${Satellites.PORT}", it) }
            .getOrNull() ?: return stopSelf()
        multicast = applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock(TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
        listeners += notify
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        bind()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started is on: the switch shows it, and it starts again at boot.
        if (protocol != null) preferences.edit().putBoolean(ENABLED, true).apply()
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
        protocol?.close()
        multicast?.release()
        protocol = null
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
                    protocol?.satellites?.let { Tap.deliver(message, length, it) }
                }
            }
        }
        // Shairport let go of the tap: the players stop too, unless a newer tap goes on. Still
        // bound, Shairport is there: another app asked for its tap since (the last one gets it).
        if (this.socket === socket) {
            protocol?.satellites?.audio(ByteArray(0), 0, 0)
            if (shairport != null) linked(Link.TAKEN)
        }
    }

    /** The tap's socket: SOCK_SEQPACKET, so messages keep their bounds (ParcelFileDescriptor's are streams). */
    private fun seqpacketPair(): List<ParcelFileDescriptor> {
        val ends = listOf(FileDescriptor(), FileDescriptor())
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, ends[0], ends[1])
        return ends.map { ParcelFileDescriptor.dup(it).also { _ -> Os.close(it) } }
    }

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

    /** How the satellites reach Shairport's audio. */
    enum class Link { OFF, WAITING, CONNECTED, TAKEN, MISSING }

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
        private const val CHANNEL_ID = "satellites"
        private const val NOTIFICATION_ID = 1

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

        /** Each player's address and hello (JSON from a player of any make): the protocol reads it. */
        private fun report(connected: List<Pair<String, String>>) {
            players = connected.map { (address, hello) ->
                Protocol.player(address, runCatching { JSONObject(hello) }.getOrElse { JSONObject() })
            }
            listeners.forEach { it() }
        }

        /** The state in a few words: the notification's and the screen's. */
        fun summary(context: Context): String = when (link) {
            Link.OFF -> context.getString(R.string.link_off)
            Link.WAITING -> context.getString(R.string.link_waiting)
            Link.MISSING -> context.getString(R.string.link_missing)
            Link.TAKEN -> context.getString(R.string.link_taken)
            Link.CONNECTED -> context.resources.getQuantityString(R.plurals.link_connected, players.size, players.size)
        }

        @Suppress("DEPRECATION") // framework PreferenceManager, like the classic settings screen
        fun preferences(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SatellitesService::class.java))
        }
    }
}

internal const val TAG = "Satellites"

/** A service on mDNS (Android's own responder, NsdManager), until [close]. */
internal class Advertisement(
    context: Context,
    private val type: String,
    name: String,
    port: Int,
    attributes: Map<String, String> = emptyMap(),
) : NsdManager.RegistrationListener, AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)

    init {
        val info = NsdServiceInfo()
        info.serviceName = name
        info.serviceType = type
        info.port = port
        attributes.forEach { (key, value) -> runCatching { info.setAttribute(key, value) } }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, this)
    }

    override fun close() {
        runCatching { nsd.unregisterService(this) }
    }

    override fun onServiceRegistered(info: NsdServiceInfo) {
        Log.i(TAG, "Advertising \"${info.serviceName}\" ($type)")
    }

    override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "Could not advertise \"${info.serviceName}\" ($type): error $errorCode")
    }

    override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
    override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
}
