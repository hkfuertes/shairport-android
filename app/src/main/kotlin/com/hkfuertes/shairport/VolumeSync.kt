package com.hkfuertes.shairport

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.roundToInt

/**
 * Sender -> Android volume. Shairport runs with ignore_volume_control (full-scale PCM) and sends
 * `ssnc/pvol` metadata over loopback UDP to [port]; the AirPlay volume sets STREAM_MUSIC.
 *
 * ponytail: one-way on purpose. Android -> sender is left out until Shairport's AirPlay 2 remote
 * volume reaches a stable release (it is only in development, and multi-room gets it wrong).
 */
class VolumeSync(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    // IPv4 on purpose: getLoopbackAddress() is ::1 on Android, Shairport sends to 127.0.0.1.
    private val socket = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    /** Loopback UDP port Shairport must send its metadata to. */
    val port: Int = socket.localPort

    fun start() {
        Log.i(TAG, "Listening for Shairport metadata on 127.0.0.1:$port")
        Thread(::listen, "airplay-metadata").start()
    }

    fun stop() = socket.close()

    private fun listen() {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        while (true) {
            try {
                socket.receive(packet)
            } catch (_: IOException) {
                return // closed
            }
            if (packet.length < 8 || String(buffer, 0, 8, Charsets.US_ASCII) != "ssncpvol") continue
            String(buffer, 8, packet.length - 8, Charsets.US_ASCII)
                .substringBefore(',').toDoubleOrNull()?.let(::applySenderVolume)
        }
    }

    private fun applySenderVolume(airplayVolume: Double) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = toIndex(airplayVolume, max)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != index) {
            // One master volume: the sender drives Android's own, with the system volume bar.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        }
        Log.i(TAG, "AirPlay volume $airplayVolume -> music stream $index/$max")
    }

    companion object {
        private const val TAG = "Shairport"

        /** AirPlay volume is -144 (mute) or -30..0 dB, linear on the sender's slider. */
        fun toIndex(airplayVolume: Double, max: Int): Int =
            if (airplayVolume <= -30.0) 0
            else ((airplayVolume + 30.0) / 30.0 * max).roundToInt().coerceIn(0, max)
    }
}
