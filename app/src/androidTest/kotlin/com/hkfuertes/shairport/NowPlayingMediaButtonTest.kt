package com.hkfuertes.shairport

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.ViewConfiguration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Uses Android's real delayed single-tap handler; no mock framework needed. */
@Suppress("DEPRECATION")
class NowPlayingMediaButtonTest : InstrumentationTestCase() {
    fun testMetadataUpdatesDoNotCancelPlayPause() {
        val commands = LinkedBlockingQueue<Int>()
        val data = Bundle().apply {
            putString(MediaMetadata.METADATA_KEY_TITLE, "Regression test")
            putInt(NowPlaying.KEY_STATE, PlaybackState.STATE_PLAYING)
            putBoolean(NowPlaying.KEY_REMOTE, true)
        }
        lateinit var nowPlaying: NowPlaying
        instrumentation.runOnMainSync {
            nowPlaying = NowPlaying(instrumentation.targetContext) { commands.add(it) }
            nowPlaying.update(data)
            nowPlaying.enabled = true
        }
        try {
            val field = NowPlaying::class.java.getDeclaredField("session").apply { isAccessible = true }
            val controller = (field.get(nowPlaying) as MediaSession).controller
            val dispatchedAt = SystemClock.uptimeMillis()
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            instrumentation.waitForIdleSync()
            // Refresh metadata inside Android's ~300 ms double-tap timeout.
            repeat(4) {
                Thread.sleep(50)
                instrumentation.runOnMainSync { nowPlaying.update(data) }
                if (it == 0) {
                    assertTrue("First refresh missed the single-tap timeout",
                        SystemClock.uptimeMillis() - dispatchedAt < ViewConfiguration.getDoubleTapTimeout())
                    assertTrue("Play/pause already fired before the refresh", commands.isEmpty())
                }
            }
            assertEquals("Single tap must pause despite metadata refreshes",
                NowPlaying.PAUSE as Any, commands.poll(2, TimeUnit.SECONDS))
            assertTrue(commands.isEmpty())

            // A stable callback must still respect changes in remote availability.
            instrumentation.runOnMainSync {
                data.putBoolean(NowPlaying.KEY_REMOTE, false)
                nowPlaying.update(data)
            }
            controller.transportControls.pause()
            instrumentation.waitForIdleSync()
            assertTrue(commands.isEmpty())
            instrumentation.runOnMainSync {
                data.putBoolean(NowPlaying.KEY_REMOTE, true)
                nowPlaying.update(data)
            }
            controller.transportControls.pause()
            assertEquals("Remote control must resume when available",
                NowPlaying.PAUSE as Any, commands.poll(2, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { nowPlaying.release() }
        }
    }
}
