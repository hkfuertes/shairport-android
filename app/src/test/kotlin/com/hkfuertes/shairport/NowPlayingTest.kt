package com.hkfuertes.shairport

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTest {
    @Test
    fun progressCarriesOnWhilePlaying() {
        val progress = NowPlaying.Progress()
        progress.update(-1, true, 0) // playing, no position from the sender yet
        assertEquals(-1L, progress.position)
        progress.update(10_000, true, 1_000) // the sender's report
        assertEquals(10_000L, progress.position)
        progress.update(10_000, false, 4_000) // paused (classic AirPlay: no new report)
        assertEquals(13_000L, progress.position)
        progress.update(10_000, true, 9_000) // resumed: nothing played while paused
        assertEquals(13_000L, progress.position)
        assertEquals(9_000L, progress.at)
        progress.update(2_000, true, 9_500) // a seek
        assertEquals(2_000L, progress.position)
    }

    /** Linked volume: what we send the sender maps back to the same index, so it never echoes. */
    @Test
    fun senderVolumeRoundTrips() {
        for (max in listOf(15, 25, 100, 160)) {
            for (index in 0..max) {
                assertEquals("$index/$max", index, VolumeSync.toIndex(VolumeSync.toAirplayVolume(index, max), max))
            }
        }
    }
}
