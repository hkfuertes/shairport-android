package com.hkfuertes.shairport

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Live state of each part of the receiver, shown in the settings screen. Written by
 * ReceiverService (supervisor markers, Wi-Fi) and VolumeSync (Shairport's play metadata).
 */
object EngineStatus {
    @Volatile var nqptp = false
        private set
    @Volatile var shairport = false
        private set
    /** Wi-Fi IPv4 TinySVCmDNS advertises on while Shairport runs. */
    @Volatile var address: String? = null
        private set
    /** Sender currently playing to us, null when idle. */
    @Volatile var source: String? = null
        private set
    @Volatile private var sourceName: String? = null
    /** Current track from Shairport's DAAP metadata (`minm`, `asar`, `asal`), null when unknown. */
    @Volatile var title: String? = null
        private set
    @Volatile var artist: String? = null
        private set
    @Volatile var album: String? = null
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)
    private fun changed() = listeners.forEach { it() }

    fun engineStarting(address: String?) {
        this.address = address
        changed()
    }

    fun nqptpRunning(running: Boolean) {
        nqptp = running
        changed()
    }

    fun shairportRunning(running: Boolean) {
        shairport = running
        if (!running) playing(false)
        changed()
    }

    fun engineStopped() {
        nqptp = false
        shairport = false
        playing(false)
    }

    /** Shairport metadata: `snam` names the sender, `pbeg`/`pend` bracket playback. */
    fun senderName(name: String) {
        sourceName = name
    }

    fun playing(playing: Boolean) {
        source = if (playing) sourceName ?: "AirPlay" else null
        if (!playing) newTrack()
        changed()
    }

    /** `ssnc/mdst` opens a new metadata bundle: forget the previous track's fields. */
    fun newTrack() {
        title = null
        artist = null
        album = null
    }

    fun track(code: String, value: String) {
        when (code) {
            "minm" -> title = value
            "asar" -> artist = value
            "asal" -> album = value
        }
    }
}
