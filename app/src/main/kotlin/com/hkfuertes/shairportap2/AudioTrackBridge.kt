package com.hkuertes.shairportap2

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer

object AudioTrackBridge {
    private var track: AudioTrack? = null
    private var playbackHead = 0L
    private var playbackHeadWraps = 0L

    @JvmStatic
    @Synchronized
    fun create(sampleRate: Int, bufferFrames: Int): Int {
        release()
        val minimumBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBytes <= 0) return minimumBytes

        return try {
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimumBytes, bufferFrames * BYTES_PER_FRAME))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                AudioTrack.ERROR_BAD_VALUE
            } else {
                track = created
                playbackHead = 0
                playbackHeadWraps = 0
                0
            }
        } catch (_: IllegalArgumentException) {
            AudioTrack.ERROR_BAD_VALUE
        }
    }

    @JvmStatic
    @Synchronized
    fun start(): Int {
        val current = track ?: return AudioTrack.ERROR_INVALID_OPERATION
        return try {
            current.play()
            0
        } catch (_: IllegalStateException) {
            AudioTrack.ERROR_INVALID_OPERATION
        }
    }

    @JvmStatic
    @Synchronized
    fun write(buffer: ByteBuffer, bytes: Int): Int {
        val current = track ?: return AudioTrack.ERROR_INVALID_OPERATION
        if (!buffer.isDirect || bytes !in 0..buffer.remaining()) return AudioTrack.ERROR_BAD_VALUE
        return try {
            current.write(buffer, bytes, AudioTrack.WRITE_BLOCKING)
        } catch (_: IllegalStateException) {
            AudioTrack.ERROR_INVALID_OPERATION
        }
    }

    @JvmStatic
    @Synchronized
    fun flush() {
        runCatching { track?.flush() }
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        runCatching { track?.stop() }
    }

    @JvmStatic
    @Synchronized
    fun release() {
        val current = track ?: return
        track = null
        runCatching { current.stop() }
        current.release()
        playbackHead = 0
        playbackHeadWraps = 0
    }

    @JvmStatic
    @Synchronized
    fun playbackHeadFrames(): Long {
        val current = track ?: return 0
        val currentHead = current.playbackHeadPosition.toLong() and 0xffffffffL
        if (currentHead < playbackHead && playbackHead - currentHead > 0x80000000L) {
            playbackHeadWraps++
        }
        playbackHead = currentHead
        return playbackHeadWraps * (1L shl 32) + currentHead
    }

    private const val BYTES_PER_FRAME = 4 // S16_LE stereo
}
