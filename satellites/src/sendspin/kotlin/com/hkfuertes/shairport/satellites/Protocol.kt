package com.hkfuertes.shairport.satellites

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Sendspin, this app's protocol (SatellitesService's): the server ([Satellites]), advertised for
 * players that look for one ([SERVICE], named like the device), and players that wait for a server
 * (`_sendspin._tcp`) found and connected to.
 */
internal class Protocol(
    context: Context,
    log: (String) -> Unit,
    changed: (List<Pair<String, String>>) -> Unit,
) : AutoCloseable {
    private val name = deviceName(context)
    val satellites = Satellites(name, identity(context), log = log, changed = changed)
    private val advertisement =
        Advertisement(context, SERVICE, name, Satellites.PORT, mapOf("path" to Satellites.PATH, "name" to name))
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val discovery = PlayerDiscovery(nsd, satellites).also { nsd.discoverServices(PLAYER_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }

    override fun close() {
        discovery.stop()
        runCatching { nsd.stopServiceDiscovery(discovery) }
        advertisement.close()
        satellites.close()
    }

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

    companion object {
        /** How players that look for a server find it. */
        const val SERVICE = "_sendspin-server._tcp"
        private const val PLAYER_TYPE = "_sendspin._tcp"
        private const val IDENTITY_FILE = "sendspin_identity"
        private const val RESOLVE_TIMEOUT_MS = 10_000L

        /** A player from its client/hello: JSON from a player of any make, every field optional. */
        fun player(address: String, hello: JSONObject): SatellitesService.Player {
            val device = hello.optJSONObject("device_info") ?: JSONObject()
            val client = listOf(device.optString("product_name"), device.optString("software_version")).filter { it.isNotBlank() }
            return SatellitesService.Player(hello.optString("name").ifBlank { address }, address, client.joinToString(" "))
        }

        /** Our Sendspin server_id's private key: made once, so players keep recognising us. */
        private fun identity(context: Context): ByteArray {
            val file = File(context.filesDir, IDENTITY_FILE)
            runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size == 32 }?.let { return it }
            return Noise.privateKey().also { key ->
                runCatching { file.writeBytes(key) }.onFailure { Log.w(TAG, "Identity not saved", it) }
            }
        }

        /** Android's device name (Settings > About phone), as Shairport's default name. */
        private fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
