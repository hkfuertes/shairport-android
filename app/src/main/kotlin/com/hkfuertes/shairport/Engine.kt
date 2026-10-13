package com.hkfuertes.shairport

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Shairport Sync as a JNI library (native/patches/shairport-sync/0008), in the :engine process.
 * One run per process: Shairport ends the process when it exits, so every start is a fresh one.
 */
object Engine {
    init {
        System.loadLibrary("shairport_sync")
    }

    /** Shairport's main(). Never returns: Shairport's exit ends the process. */
    @JvmStatic external fun run(arguments: Array<String>): Int

    /** Asks Shairport to exit cleanly (mDNS goodbyes, AAudio closed), which ends the process. */
    @JvmStatic external fun stop()

    /** Patch 0009: hand every buffer AAudio plays to [satelliteAudio]; [only]: AAudio plays zeros. */
    @JvmStatic private external fun relayAudio(on: Boolean, only: Boolean)

    @Volatile private var satellites: Satellites? = null

    /**
     * Sendspin server for satellites, for the rest of this process (one engine run): advertised for
     * players that connect themselves, and connecting to those that wait for a server.
     */
    fun startSatellites(name: String, identity: ByteArray, only: Boolean) {
        val server = runCatching { Satellites(name, identity, log = { Log.i(TAG, it) }, changed = ::reportSatellites) }
            .onFailure { Log.e(TAG, "Satellites: could not listen on ${Satellites.PORT}", it) }
            .getOrNull() ?: return
        satellites = server
        relayAudio(true, only)
        val txt = arrayOf("path=${Satellites.PATH}", "name=$name").map { it.toByteArray() }.toTypedArray()
        mdnsPublish("_sendspin-server._tcp", name.toByteArray(), Satellites.PORT, txt)
        nsd.discoverServices("_sendspin._tcp", NsdManager.PROTOCOL_DNS_SD, PlayerDiscovery(server))
    }

    /**
     * Players that wait for a server (`_sendspin._tcp`): resolved one at a time (older NsdManagers
     * refuse concurrent resolves), then [Satellites.connect]ed while advertised.
     */
    private class PlayerDiscovery(private val satellites: Satellites) : NsdManager.DiscoveryListener {
        private val resolver = Executors.newSingleThreadExecutor()

        override fun onServiceFound(info: NsdServiceInfo) {
            resolver.execute {
                val done = CountDownLatch(1)
                @Suppress("DEPRECATION") // resolveService: the replacement is API 34+
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host
                        // ponytail: IPv4 only; a link-local IPv6 address needs its scope in the URI.
                        if (host is Inet4Address) {
                            val path = resolved.attributes["path"]?.let { String(it, Charsets.UTF_8) } ?: Satellites.PATH
                            Log.i(TAG, "Satellites: found player \"${resolved.serviceName}\" at ${host.hostAddress}:${resolved.port}")
                            satellites.connect(resolved.serviceName, URI("ws", null, host.hostAddress, resolved.port, path, null, null))
                        }
                        done.countDown()
                    }

                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Satellites: could not resolve \"${failed.serviceName}\": error $errorCode")
                        done.countDown()
                    }
                })
                done.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) = satellites.forget(info.serviceName)
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Satellites: could not look for players: error $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    /** Sendspin volume for every satellite, in percent (linked volume: EngineService). */
    fun satelliteVolume(percent: Int) {
        satellites?.volume = percent
    }

    private fun reportSatellites(connected: List<Pair<String, String>>) {
        val data = Bundle().apply {
            putStringArrayList(EngineService.KEY_ADDRESSES, ArrayList(connected.map { it.first }))
            putStringArrayList(EngineService.KEY_HELLOS, ArrayList(connected.map { it.second }))
        }
        runCatching { status?.send(Message.obtain(null, EngineService.MSG_SATELLITES).apply { this.data = data }) }
    }

    /** Patch 0009, on Shairport's player thread: see [Satellites.audio]. */
    @JvmStatic
    fun satelliteAudio(pcm: ByteArray, rate: Int, heardAt: Long) {
        satellites?.audio(pcm, rate, heardAt)
    }

    internal lateinit var nsd: NsdManager
    internal var status: Messenger? = null
    /** Written by Shairport's threads (synchronized), read by NsdManager's callbacks. */
    private val services = ConcurrentHashMap<String, Registration>()

    /**
     * Shairport's "android" mDNS backend: registers one service type with NsdManager. NsdManager
     * can't update a TXT record, so a new one (AirPlay 2 group changes) replaces the service.
     */
    @JvmStatic
    @Synchronized
    fun mdnsPublish(type: String, name: ByteArray, port: Int, txt: Array<ByteArray>) {
        services.remove(type)?.unregister()
        val info = NsdServiceInfo().apply {
            serviceName = String(name, Charsets.UTF_8)
            serviceType = type
            setPort(port)
        }
        for (record in txt.map { String(it, Charsets.UTF_8) }) {
            runCatching { info.setAttribute(record.substringBefore('='), record.substringAfter('=', "")) }
                .onFailure { Log.w(TAG, "mDNS: TXT \"$record\" dropped", it) }
        }
        val registration = Registration(type)
        services[type] = registration
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    /** Shairport is exiting: goodbyes now (the system would also drop them with the process). */
    @JvmStatic
    @Synchronized
    fun mdnsUnpublish() {
        services.values.forEach { it.unregister() }
        services.clear()
        report()
    }

    private fun report() {
        val raop = services["_raop._tcp"]?.registered == true
        val airplay = services["_airplay._tcp"]?.registered == true
        runCatching {
            status?.send(Message.obtain(null, EngineService.MSG_ADVERTISED, if (raop || airplay) 1 else 0, if (airplay) 1 else 0))
        }
    }

    private class Registration(val type: String) : NsdManager.RegistrationListener {
        @Volatile var registered = false
        private val unregistered = CountDownLatch(1)

        fun unregister() {
            runCatching { nsd.unregisterService(this) }.onFailure { return } // never registered
            // The same name is registered again right away: let the old one go first.
            unregistered.await(UNREGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS: advertising \"${info.serviceName}\" ($type)")
            registered = true
            report()
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "mDNS: could not advertise \"${info.serviceName}\" ($type): error $errorCode")
            report()
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registered = false
            unregistered.countDown()
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = unregistered.countDown()
    }

    private const val TAG = "Shairport"
    private const val UNREGISTER_TIMEOUT_MS = 1000L
    private const val RESOLVE_TIMEOUT_MS = 10_000L
}

/** Hosts [Engine] in the :engine process: ReceiverService binds it to start and unbinds to stop. */
class EngineService : Service() {
    private var volumeReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent): IBinder {
        if (started) { // a stopping engine's process was reused: never run Shairport twice in one
            Log.e(TAG, "Engine process reused; restarting it")
            Process.killProcess(Process.myPid())
        }
        started = true
        Engine.nsd = getSystemService(NsdManager::class.java)
        Engine.status = messenger(intent)
        Os.setenv("NQPTP_SHM_DIRECTORY", intent.getStringExtra(EXTRA_SHM_DIRECTORY).orEmpty(), true)
        if (intent.getBooleanExtra(EXTRA_SATELLITES, false)) {
            Engine.startSatellites(
                intent.getStringExtra(EXTRA_NAME).orEmpty(),
                identity(),
                intent.getBooleanExtra(EXTRA_SATELLITES_ONLY, false),
            )
            if (intent.getBooleanExtra(EXTRA_LINKED_VOLUME, false)) followMusicVolume()
        }
        val arguments = requireNotNull(intent.getStringArrayExtra(EXTRA_ARGUMENTS))
        Thread({ Engine.run(arguments) }, "shairport").start()
        return Binder()
    }

    /**
     * Linked volume: Shairport plays at full scale and VolumeSync sets this device's music volume
     * from the sender's. Satellites follow that volume, wherever it is set (sender, keys, Home Assistant).
     */
    private fun followMusicVolume() {
        val audio = getSystemService(AudioManager::class.java)
        val update = {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Engine.satelliteVolume((audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt())
        }
        update()
        // Not in the SDK, but AudioService sends it on every volume change (a protected system broadcast).
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = update()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        volumeReceiver = receiver
    }

    /** Our Sendspin server_id's private key: made once, so players keep recognising us. */
    private fun identity(): ByteArray {
        val file = File(filesDir, IDENTITY_FILE)
        runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size == 32 }?.let { return it }
        return Noise.privateKey().also { key ->
            runCatching { file.writeBytes(key) }.onFailure { Log.w(TAG, "Satellites: identity not saved", it) }
        }
    }

    override fun onDestroy() {
        volumeReceiver?.let { unregisterReceiver(it) }
        if (started) Engine.stop() // Shairport's exit ends this process
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // the typed getParcelableExtra is API 33+
    private fun messenger(intent: Intent): Messenger? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_STATUS, Messenger::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_STATUS)
        }

    companion object {
        const val EXTRA_ARGUMENTS = "arguments"
        const val EXTRA_SHM_DIRECTORY = "shm_directory"
        const val EXTRA_STATUS = "status"
        const val EXTRA_SATELLITES = "satellites"
        /** Satellites play, this device stays silent. */
        const val EXTRA_SATELLITES_ONLY = "satellites_only"
        /** The receiver's name: the Sendspin server's too. */
        const val EXTRA_NAME = "name"
        private const val IDENTITY_FILE = "sendspin_identity"
        /** Satellites play at this device's music volume (the PCM is at full scale). */
        const val EXTRA_LINKED_VOLUME = "linked_volume"
        /** arg1: advertised at all; arg2: as AirPlay 2 (`_airplay._tcp`). */
        const val MSG_ADVERTISED = 1
        /** data: [KEY_ADDRESSES] and [KEY_HELLOS] of the connected satellites. */
        const val MSG_SATELLITES = 2
        const val KEY_ADDRESSES = "addresses"
        const val KEY_HELLOS = "hellos"
        private const val TAG = "Shairport"
        private var started = false
    }
}
