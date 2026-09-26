package com.hkfuertes.shairport

import android.app.Service
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.system.Os
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shairport Sync as a JNI library (native/patches/shairport-sync/0003), in the :engine process.
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
    override fun onBind(intent: Intent): IBinder {
        if (started) { // a stopping engine's process was reused: never run Shairport twice in one
            Log.e(TAG, "Engine process reused; restarting it")
            Process.killProcess(Process.myPid())
        }
        started = true
        Engine.nsd = getSystemService(NsdManager::class.java)
        Engine.status = messenger(intent)
        Os.setenv("NQPTP_SHM_DIRECTORY", intent.getStringExtra(EXTRA_SHM_DIRECTORY).orEmpty(), true)
        val arguments = requireNotNull(intent.getStringArrayExtra(EXTRA_ARGUMENTS))
        Thread({ Engine.run(arguments) }, "shairport").start()
        return Binder()
    }

    override fun onDestroy() {
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
        /** arg1: advertised at all; arg2: as AirPlay 2 (`_airplay._tcp`). */
        const val MSG_ADVERTISED = 1
        private const val TAG = "Shairport"
        private var started = false
    }
}
