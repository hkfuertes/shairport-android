package com.hkfuertes.shairport.satellites

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Chromecast, this app's protocol (SatellitesService's): the Google Cast devices on the network
 * (`_googlecast._tcp`, their TXT: `fn` the name, `md` the model, `ca` the capabilities), found
 * one at a time and cast to. Nothing of ours is advertised: the devices pull.
 */
internal class Protocol(
    context: Context,
    log: (String) -> Unit,
    private val changed: (List<Pair<String, String>>) -> Unit,
) : AutoCloseable {
    val satellites = Satellites(log = log)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val devices = ConcurrentHashMap<String, Device>() // by mDNS name: stable per device
    private val discovery = Discovery(nsd).also { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, it) }

    override fun close() {
        discovery.stop()
        runCatching { nsd.stopServiceDiscovery(discovery) }
        satellites.close()
    }

    /** A device, found: what [changed] reports of it. */
    private class Device(val address: String, val port: Int, val caps: Int, val json: String)

    private fun report() {
        devices.values.let { devices -> changed(devices.map { it.address to it.json }) }
    }

    /**
     * The cast devices (`_googlecast._tcp`): resolved one at a time (older NsdManagers refuse
     * concurrent resolves), then cast to while they last.
     */
    private inner class Discovery(private val nsd: NsdManager) : NsdManager.DiscoveryListener {
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
                    if (host != null) {
                        val attributes = resolved.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8) ?: "" }
                        fun attribute(key: String) = attributes[key] ?: ""
                        val name = attribute("fn").ifBlank { resolved.serviceName }
                        devices[resolved.serviceName] =
                            Device(host.hostAddress, resolved.port, attribute("ca").toIntOrNull() ?: 0, JSONObject().put("fn", name).put("md", attribute("md")).toString())
                        Log.i(TAG, "Found cast device \"$name\" at ${host.hostAddress}:${resolved.port}")
                        satellites.cast(resolved.serviceName, name, host.hostAddress, resolved.port, attribute("ca").toIntOrNull() ?: 0)
                        report()
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

        override fun onServiceLost(info: NsdServiceInfo) {
            devices.remove(info.serviceName) ?: return
            satellites.forget(info.serviceName)
            report()
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Could not look for cast devices: error $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    companion object {
        /** How the cast devices are found; nothing of ours is advertised. */
        const val SERVICE = "_googlecast._tcp"
        private const val TAG = "Satellites"
        private const val RESOLVE_TIMEOUT_MS = 10_000L

        /** A device from its mDNS: [hello] is the TXT the Protocol kept (fn, md), every field optional. */
        fun player(address: String, hello: JSONObject): SatellitesService.Player =
            SatellitesService.Player(hello.optString("fn").ifBlank { address }, address, hello.optString("md"))
    }
}
