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
import java.util.concurrent.ConcurrentHashMap
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
    private val context: Context,
    log: (String) -> Unit,
    private val changed: (List<Pair<String, String>>) -> Unit,
) : AutoCloseable {
    private val name = deviceName(context)

    /** The players found waiting for a server, by their mDNS name: so the switched-off ones stay on the list. */
    private val players = ConcurrentHashMap<String, Pair<URI, String>>()
    val satellites = Satellites(name, identity(context), log = log) { connected -> report(connected) }
    private val advertisement =
        Advertisement(context, SERVICE, name, Satellites.PORT, mapOf("path" to Satellites.PATH, "name" to name))
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val discovery = PlayerDiscovery(nsd, satellites) { id, uri, playerName ->
        players[id] = uri to playerName
        if (id !in switchedOff()) satellites.connect(id, uri)
    }.also { nsd.discoverServices(PLAYER_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }

    override fun close() {
        discovery.stop()
        runCatching { nsd.stopServiceDiscovery(discovery) }
        advertisement.close()
        satellites.close()
    }

    /** Plus the players found and switched off: still on the list, so their switch can be turned on again. */
    private fun report(connected: List<Pair<String, String>>) {
        changed(
            connected + players.filter { (id, _) -> id !in connected.toMap() && id !in satellites.dialing }.map { (id, player) ->
                id to JSONObject().put("name", player.second.ifBlank { id }).put("at", player.first.host ?: "").toString()
            },
        )
    }

    /** The players switched off: the found ones among them are not dialed (again), the connected ones get no audio. */
    fun apply() {
        val off = switchedOff()
        satellites.off = off
        players.forEach { (id, player) ->
            if (id in off) satellites.forget(id) else if (id !in satellites.dialing) satellites.connect(id, player.first)
        }
    }

    private fun switchedOff(): Set<String> =
        SatellitesService.preferences(context).getStringSet(SatellitesService.PLAYERS_OFF, emptySet()).orEmpty()

    /**
     * Players that wait for a server (`_sendspin._tcp`): resolved one at a time (older NsdManagers
     * refuse concurrent resolves), then [Satellites.connect]ed while advertised.
     */
    private class PlayerDiscovery(private val nsd: NsdManager, private val satellites: Satellites, private val found: (String, URI, String) -> Unit) : NsdManager.DiscoveryListener {
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
                        found(
                            resolved.serviceName,
                            URI("ws", null, host.hostAddress, resolved.port, path, null, null),
                            resolved.attributes["name"]?.let { String(it, Charsets.UTF_8) } ?: "",
                        )
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
            return SatellitesService.Player(hello.optString("name").ifBlank { address }, address, hello.optString("at"), client.joinToString(" "))
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
