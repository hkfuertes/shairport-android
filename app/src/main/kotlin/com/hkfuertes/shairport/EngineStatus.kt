package com.hkfuertes.shairport

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Live state of each part of the receiver, shown in the settings screen. Written by
 * ReceiverService (engine process, NQPTP watcher, Wi-Fi) and VolumeSync (Shairport's metadata).
 */
object EngineStatus {
    @Volatile var nqptp = false
        private set
    @Volatile var shairport = false
        private set
    /** Registered with NsdManager, at all and as an AirPlay 2 receiver (`_airplay._tcp`). */
    @Volatile var advertising = false
        private set
    @Volatile var airplay2 = false
        private set
    /** Current Wi-Fi IPv4 address. */
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

    fun wifiAddress(address: String?) {
        this.address = address
        changed()
    }

    fun advertised(advertising: Boolean, airplay2: Boolean) {
        this.advertising = advertising
        this.airplay2 = airplay2
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
        shairport = false
        advertising = false
        airplay2 = false
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
