package com.hkfuertes.shairport.satellites

import android.content.Context
import org.json.JSONObject

/**
 * Snapcast, this app's protocol (SatellitesService's): the server ([Satellites]), advertised for
 * the clients that look for one ([SERVICE]); the others are given the device's address.
 */
internal class Protocol(
    context: Context,
    log: (String) -> Unit,
    changed: (List<Pair<String, String>>) -> Unit,
) : AutoCloseable {
    val satellites = Satellites(log = log, changed = changed)
    // Snapdroid only takes a server whose name starts with "Snapcast": snapserver's own name.
    private val advertisement = Advertisement(context, SERVICE, "Snapcast", Satellites.PORT)

    override fun close() {
        advertisement.close()
        satellites.close()
    }

    companion object {
        /** How clients that look for a server find it. */
        const val SERVICE = "_snapcast._tcp"

        /** A client from its Hello (snapclient's: HostName, ClientName, Version...). */
        fun player(address: String, hello: JSONObject): SatellitesService.Player {
            val client = listOf(hello.optString("ClientName"), hello.optString("Version")).filter { it.isNotBlank() }
            return SatellitesService.Player(hello.optString("HostName").ifBlank { address }, address, client.joinToString(" "))
        }
    }
}
