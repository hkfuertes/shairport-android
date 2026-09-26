package com.hkfuertes.shairportap2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Two-way volume sync between the AirPlay sender and Android's music stream.
 *
 * Sender -> Android: Shairport sends `ssnc/pvol` metadata over UDP to [port] and we set
 * STREAM_MUSIC (Shairport itself runs with ignore_volume_control, i.e. full-scale PCM).
 * Android -> sender: volume key changes go back over DACP (`dmcp.device-volume`), with the
 * sender's DACP port resolved from `iTunes_Ctrl_<DACP-ID>._dacp._tcp` through NsdManager.
 */
class VolumeSync(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    // IPv4 on purpose: getLoopbackAddress() is ::1 on Android, Shairport sends to 127.0.0.1.
    private val socket = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var appliedIndex = -1
    @Volatile private var dacpId: String? = null
    @Volatile private var activeRemote: String? = null
    @Volatile private var clientIp: String? = null
    @Volatile private var dacpAddress: InetSocketAddress? = null

    /** Loopback UDP port Shairport must send its metadata to. */
    val port: Int = socket.localPort

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val index = intent.getIntExtra(EXTRA_STREAM_VALUE, -1)
            // Ignore our own echo: the index we just applied from the sender.
            if (index < 0 || index == appliedIndex) return
            appliedIndex = index
            sender.execute { sendToSender(index) }
        }
    }

    fun start() {
        Log.i(TAG, "Listening for Shairport metadata on 127.0.0.1:$port")
        Thread(::listen, "airplay-metadata").start()
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(volumeReceiver, filter)
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(volumeReceiver) }
        socket.close()
        sender.shutdownNow()
    }

    private fun listen() {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        while (true) {
            try {
                socket.receive(packet)
            } catch (_: IOException) {
                return // closed
            }
            if (packet.length < 8 || String(buffer, 0, 4, Charsets.US_ASCII) != "ssnc") continue
            val data = String(buffer, 8, packet.length - 8, Charsets.UTF_8)
            when (String(buffer, 4, 4, Charsets.US_ASCII)) {
                "pvol" -> data.substringBefore(',').toDoubleOrNull()?.let(::applySenderVolume)
                "daid" -> {
                    dacpId = data
                    dacpAddress = null
                }
                "acre" -> activeRemote = data
                "clip" -> clientIp = data
                "disc" -> {
                    dacpId = null
                    activeRemote = null
                    clientIp = null
                    dacpAddress = null
                }
            }
        }
    }

    private fun applySenderVolume(airplayVolume: Double) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = toIndex(airplayVolume, max)
        appliedIndex = index
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != index) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }
        Log.i(TAG, "AirPlay volume $airplayVolume -> music stream $index/$max")
    }

    private fun sendToSender(index: Int) {
        val id = dacpId ?: return
        val remote = activeRemote ?: return
        val volume = toAirPlay(index, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val address = dacpAddress ?: resolveDacp(id)?.also { dacpAddress = it } ?: return
        val request = String.format(
            Locale.US,
            "GET /ctrl-int/1/setproperty?dmcp.device-volume=%.6f HTTP/1.1\r\n" +
                "Host: %s:%d\r\nActive-Remote: %s\r\nConnection: close\r\n\r\n",
            volume, address.address.hostAddress, address.port, remote,
        )
        try {
            // ponytail: raw HTTP/1.1 so Android's cleartext policy stays untouched.
            Socket().use { socket ->
                socket.connect(address, TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                val status = socket.getInputStream().bufferedReader().readLine()
                Log.i(TAG, "DACP device-volume $volume -> $status")
            }
        } catch (error: IOException) {
            Log.w(TAG, "DACP device-volume failed", error)
            dacpAddress = null
        }
    }

    @Suppress("DEPRECATION") // NsdServiceInfo.host: fine for a single-address LAN sender.
    private fun resolveDacp(id: String): InetSocketAddress? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val service = NsdServiceInfo().apply {
            // The service name keeps leading zeros, the DACP-ID header may not.
            serviceName = "iTunes_Ctrl_" + id.padStart(16, '0')
            serviceType = "_dacp._tcp"
        }
        val done = CountDownLatch(1)
        var result: InetSocketAddress? = null
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Could not resolve ${info.serviceName}: $errorCode")
                done.countDown()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                // Like Shairport: talk to the RTSP client's address, only the port comes from
                // mDNS (NSD may hand back an unscoped IPv6 link-local host).
                val host = clientIp?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                result = InetSocketAddress(host ?: info.host, info.port)
                done.countDown()
            }
        }
        nsd.resolveService(service, listener)
        if (!done.await(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            runCatching { nsd.stopServiceResolution(listener) }
        }
        Log.i(TAG, "DACP ${service.serviceName} -> $result")
        return result
    }

    companion object {
        private const val TAG = "ShairportAP2"
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val TIMEOUT_MS = 2000
        private const val RESOLVE_TIMEOUT_SECONDS = 5L

        /** AirPlay volume is -144 (mute) or -30..0 dB, linear on the sender's slider. */
        fun toIndex(airplayVolume: Double, max: Int): Int =
            if (airplayVolume <= -30.0) 0
            else ((airplayVolume + 30.0) / 30.0 * max).roundToInt().coerceIn(0, max)

        fun toAirPlay(index: Int, max: Int): Double =
            if (index <= 0 || max <= 0) -144.0 else -30.0 + 30.0 * index.coerceAtMost(max) / max
    }
}
