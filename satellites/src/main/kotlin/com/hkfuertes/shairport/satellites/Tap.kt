package com.hkfuertes.shairport.satellites

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shairport's audio tap, from this side: what the receiver's AudioTapService (com.hkfuertes.shairport,
 * whose documentation this follows) takes and sends. No android.* here: the unit test runs it.
 */
object Tap {
    const val PACKAGE = "com.hkfuertes.shairport"
    const val ACTION = "com.hkfuertes.shairport.AUDIO_TAP"
    const val MSG_OPEN = 1
    const val KEY_SOCKET = "socket"
    const val KEY_LEAD = "lead"
    const val KEY_SILENT = "silent"
    const val KEY_44100 = "only_44100"
    /** How early the audio leaves Shairport: room for Wi-Fi hiccups on the way to the players. */
    const val LEAD_SECONDS = 1f
    /** Room for any buffer Shairport plays (a few thousand frames at most). */
    const val MAX_MESSAGE = 65536
    private const val AUDIO = 0
    private const val FLUSH = 1
    private const val VOLUME = 2
    private const val HEADER = 16

    /**
     * One message ([length] bytes of [message]) to [satellites]: int32 type, int32 value, int64
     * time, little-endian (every Android ABI), then for audio its 16-bit stereo frames.
     */
    fun deliver(message: ByteArray, length: Int, satellites: Satellites) {
        if (length < HEADER) return
        val header = ByteBuffer.wrap(message, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        when (header.getInt(0)) {
            AUDIO -> satellites.audio(message.copyOfRange(HEADER, length), header.getInt(4), header.getLong(8))
            FLUSH -> satellites.audio(ByteArray(0), 0, 0)
            VOLUME -> satellites.volume = header.getInt(4)
        }
    }
}
