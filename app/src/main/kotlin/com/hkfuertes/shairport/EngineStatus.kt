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
        if (!running) source = null
        changed()
    }

    fun engineStopped() {
        nqptp = false
        shairport = false
        source = null
        changed()
    }

    /** Shairport metadata: `snam` names the sender, `pbeg`/`pend` bracket playback. */
    fun senderName(name: String) {
        sourceName = name
    }

    fun playing(playing: Boolean) {
        source = if (playing) sourceName ?: "AirPlay" else null
        changed()
    }
}
