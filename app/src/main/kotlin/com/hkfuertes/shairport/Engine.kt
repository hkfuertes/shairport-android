package com.hkfuertes.shairport

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.system.Os
import android.util.Log
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

    /**
     * Patch 0009, AudioTapService: every buffer AAudio plays goes to the socket [fd] (-1: none),
     * which Shairport owns from now on; [silent]: AAudio plays zeros meanwhile.
     */
    @JvmStatic external fun audioTap(fd: Int, silent: Boolean)

    /** Patch 0009: the volume the audio tap's app plays at, in percent (100 until linked volume sets it). */
    @JvmStatic external fun tapVolume(percent: Int)

    /** Patch 0010: the sender's remote control (Shairport's simple_command_t), AirPlay 2 only. */
    @JvmStatic private external fun remoteCommand(command: Int)

    /** Patch 0010: the sender's volume, AirPlay's -30..0 dB or -144 (mute), AirPlay 2 only. */
    @JvmStatic private external fun remoteVolume(airplayVolume: Double)

    /** Remote control blocks (AirPlay 2's volume goes in steps, ~0.1 s): one call at a time. */
    private val remote = Executors.newSingleThreadExecutor()

    /** The sender's volume as Shairport's metadata hub last had it, NaN before. */
    @Volatile private var airplayVolume = Double.NaN

    /** A [NowPlaying] button or control, from ReceiverService. */
    fun remote(command: Int) {
        if (command in NowPlaying.PLAY..NowPlaying.PREVIOUS) remote.execute { remoteCommand(command) }
    }

    /**
     * Linked volume (EngineService): Android's music volume becomes the sender's, unless it already
     * is, as when VolumeSync has just set it from the sender's: no echo.
     */
    fun senderVolume(index: Int, max: Int) {
        val volume = airplayVolume
        if (volume.isNaN() || VolumeSync.toIndex(volume, max) == index) return
        remote.execute { remoteVolume(VolumeSync.toAirplayVolume(index, max)) }
    }

    /**
     * Patch 0010: Shairport's metadata hub changed (on that Shairport thread). For ReceiverService's
     * [NowPlaying] and status: [state] is Shairport's play_status_type, [positionMs] where the
     * sender last said the track was (-1: unknown), [airplay2] whether [remote] reaches the sender.
     */
    @JvmStatic
    fun nowPlaying(
        title: ByteArray?, artist: ByteArray?, album: ByteArray?, artwork: ByteArray?,
        state: Int, durationMs: Long, positionMs: Long, airplayVolume: Double, airplay2: Boolean,
    ) {
        this.airplayVolume = airplayVolume
        fun text(bytes: ByteArray?) = bytes?.let { String(it, Charsets.UTF_8) }
        val data = Bundle().apply {
            putString(MediaMetadata.METADATA_KEY_TITLE, text(title))
            putString(MediaMetadata.METADATA_KEY_ARTIST, text(artist))
            putString(MediaMetadata.METADATA_KEY_ALBUM, text(album))
            putString(MediaMetadata.METADATA_KEY_ART_URI, text(artwork))
            putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            putInt(NowPlaying.KEY_STATE, state)
            putLong(NowPlaying.KEY_POSITION, positionMs)
            putBoolean(NowPlaying.KEY_REMOTE, airplay2)
        }
        runCatching { status?.send(Message.obtain(null, EngineService.MSG_NOW_PLAYING).apply { this.data = data }) }
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
        if (intent.getBooleanExtra(EXTRA_LINKED_VOLUME, false)) followMusicVolume()
        val arguments = requireNotNull(intent.getStringArrayExtra(EXTRA_ARGUMENTS))
        Thread({ Engine.run(arguments) }, "shairport").start()
        // ReceiverService's way in: the now playing notification's controls, the audio tap.
        return Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                MSG_REMOTE_COMMAND -> Engine.remote(message.arg1)
                MSG_TAP -> Engine.audioTap(
                    AudioTapService.parcelable(message.data, AudioTapService.KEY_SOCKET)?.detachFd() ?: -1,
                    message.data.getBoolean(AudioTapService.KEY_SILENT),
                )
            }
            true
        }).binder
    }

    /**
     * Linked volume: Shairport plays at full scale and VolumeSync sets this device's music volume
     * from the sender's. The audio tap's app and the sender (AirPlay 2) follow that volume,
     * wherever it is set (sender, keys, Home Assistant).
     */
    private fun followMusicVolume() {
        val audio = getSystemService(AudioManager::class.java)
        val update = {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            Engine.tapVolume((index * 100f / max).roundToInt())
            Engine.senderVolume(index, max)
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
        /** The audio tap and the sender follow this device's music volume (the PCM is at full scale). */
        const val EXTRA_LINKED_VOLUME = "linked_volume"
        /** arg1: advertised at all; arg2: as AirPlay 2 (`_airplay._tcp`). */
        const val MSG_ADVERTISED = 1
        /** data: the track and state from Engine.nowPlaying (MediaMetadata's and [NowPlaying]'s keys). */
        const val MSG_NOW_PLAYING = 3
        /** To the engine (its binder's Messenger), arg1: a [NowPlaying] command. */
        const val MSG_REMOTE_COMMAND = 4
        /** To the engine, data: AudioTapService's KEY_SOCKET (none: no tap) and KEY_SILENT. */
        const val MSG_TAP = 5
        private const val TAG = "Shairport"
        private var started = false
    }
}
