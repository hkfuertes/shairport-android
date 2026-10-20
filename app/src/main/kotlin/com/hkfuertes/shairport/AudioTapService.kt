package com.hkfuertes.shairport

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The audio tap: another app gets what the receiver plays, as it plays it, to do with as it
 * likes (Sendspin satellites, ...). Only apps signed with the same key (the service's permission).
 *
 * The app binds [ACTION] and sends [MSG_OPEN]: a SOCK_SEQPACKET socket ([KEY_SOCKET], from
 * Os.socketpair: ParcelFileDescriptor's pairs are streams) and what it needs from the receiver.
 * ReceiverService hands the socket to the engine (patch 0009), which sends one message per
 * buffer, in native byte order (little-endian): int32 type, int32 value, int64 time, then for
 * [AUDIO] its frames.
 *
 * - [AUDIO]: value the rate (Hz), time when its first frame is heard (System.nanoTime(), the same
 *   clock in every process), then 16-bit stereo frames. They leave about [KEY_LEAD] ahead.
 * - [FLUSH]: pause, skip or stop: drop what you hold.
 * - [VOLUME]: value the volume to play at, in percent. 100 unless the receiver links its volume
 *   to Android's music volume: then the audio is at full scale and this is that volume.
 *
 * Unbinding (or the app dying) ends the tap. ponytail: one tap at a time; the last open wins.
 */
class AudioTapService : Service() {
    override fun onBind(intent: Intent): IBinder = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == MSG_OPEN) open(message)
        true
    }).binder

    override fun onUnbind(intent: Intent): Boolean {
        set(null)
        return false
    }

    private fun open(message: Message) {
        val data = message.data
        // SOCK_SEQPACKET (the engine refuses any other: a stream would merge messages).
        val socket = parcelable(data, KEY_SOCKET) ?: return
        set(
            Tap(
                socket,
                data.getFloat(KEY_LEAD).coerceIn(MIN_LEAD, MAX_LEAD),
                data.getBoolean(KEY_SILENT),
                data.getBoolean(KEY_44100),
                label(message.sendingUid),
            ),
        )
    }

    /** The tapping app's name, for the status. */
    private fun label(uid: Int): String {
        val name = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return uid.toString()
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(name, 0)).toString() }
            .getOrDefault(name)
    }

    /**
     * What the tapping app asked for: [lead] seconds of head start (the AAudio buffer), [silent]
     * this device, AirPlay 2 at 44.1 kHz [only44100] (its 48 kHz lossless, AAC and surround off).
     */
    class Tap(
        val socket: ParcelFileDescriptor,
        val lead: Float,
        val silent: Boolean,
        val only44100: Boolean,
        val app: String,
    )

    companion object {
        const val ACTION = "com.hkfuertes.shairport.AUDIO_TAP"
        const val MSG_OPEN = 1
        const val KEY_SOCKET = "socket"
        /** Seconds, [MIN_LEAD] (the receiver's own) to [MAX_LEAD]: a change restarts the engine. */
        const val KEY_LEAD = "lead"
        /** This device plays silence meanwhile; applied live. */
        const val KEY_SILENT = "silent"
        /** AirPlay 2 at 44.1 kHz only, for an app that can't use 48 kHz: restarts the engine. */
        const val KEY_44100 = "only_44100"
        const val AUDIO = 0
        const val FLUSH = 1
        const val VOLUME = 2
        const val MIN_LEAD = 0.5f
        // ponytail: 1 s, the head start tested; more when an app needs it.
        const val MAX_LEAD = 1f

        @Volatile var tap: Tap? = null
            private set
        private val listeners = CopyOnWriteArraySet<() -> Unit>()

        fun addListener(listener: () -> Unit) = listeners.add(listener)
        fun removeListener(listener: () -> Unit) = listeners.remove(listener)

        private fun set(tap: Tap?) {
            val old = this.tap
            this.tap = tap
            old?.socket?.close() // the engine keeps its own copy until it gets the new one
            listeners.forEach { it() }
        }

        @Suppress("DEPRECATION") // the typed getParcelable is API 33+
        fun parcelable(data: Bundle, key: String): ParcelFileDescriptor? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) data.getParcelable(key, ParcelFileDescriptor::class.java)
            else data.getParcelable(key)
    }
}
