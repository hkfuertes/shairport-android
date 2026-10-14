package com.hkfuertes.shairport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock

/**
 * The sender's track as a media notification of its own (MediaSession + MediaStyle): artwork and
 * progress, on the lock screen and in the quick settings' media controls too. Its buttons, the
 * session's controls and media buttons go to the sender through [remote], AirPlay 2 only. Fed by
 * Shairport's metadata hub (Engine.nowPlaying, patch 0010); main thread only.
 */
class NowPlaying(private val context: Context, private val remote: (Int) -> Unit) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var session: MediaSession? = null
    private var last: Bundle = Bundle.EMPTY
    private val progress = Progress()
    private var artworkPath: String? = null
    private var artwork: Bitmap? = null

    /** The setting: off removes the notification and its session, on shows the current track. */
    var enabled = false
        set(value) {
            field = value
            if (value) show() else release()
        }

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.now_playing_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC },
        )
    }

    /** From Engine.nowPlaying, through EngineService.MSG_NOW_PLAYING; [Bundle.EMPTY]: no engine. */
    fun update(data: Bundle) {
        last = data
        progress.update(data.getLong(KEY_POSITION, -1), data.getInt(KEY_STATE) == PlaybackState.STATE_PLAYING, SystemClock.elapsedRealtime())
        show()
    }

    fun release() {
        notifications.cancel(NOTIFICATION_ID)
        session?.release()
        session = null
    }

    private fun show() {
        val state = last.getInt(KEY_STATE) // Shairport's play_status_type: PlaybackState's numbers
        val title = last.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (!enabled || title == null || state < PlaybackState.STATE_PAUSED) return release()
        val path = last.getString(MediaMetadata.METADATA_KEY_ART_URI)?.removePrefix("file://")
        if (path != artworkPath) {
            artworkPath = path
            artwork = path?.let(BitmapFactory::decodeFile)
        }
        val remote = last.getBoolean(KEY_REMOTE)
        val playing = state == PlaybackState.STATE_PLAYING
        val session = session ?: MediaSession(context, TAG).also { session = it }
        session.setCallback(if (remote) callback else null)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, last.getString(MediaMetadata.METADATA_KEY_ARTIST))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, last.getString(MediaMetadata.METADATA_KEY_ALBUM))
                .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                .apply { last.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, progress.position, if (playing) 1f else 0f, progress.at)
                .setActions(if (remote) ACTIONS else 0)
                .build(),
        )
        session.isActive = true
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_airplay_audio)
            .setLargeIcon(artwork)
            .setContentTitle(title)
            .setContentText(last.getString(MediaMetadata.METADATA_KEY_ARTIST))
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
        if (remote) {
            builder.addAction(action(android.R.drawable.ic_media_previous, R.string.now_playing_previous, PREVIOUS))
                .addAction(
                    if (playing) action(android.R.drawable.ic_media_pause, R.string.now_playing_pause, PAUSE)
                    else action(android.R.drawable.ic_media_play, R.string.now_playing_play, PLAY),
                )
                .addAction(action(android.R.drawable.ic_media_next, R.string.now_playing_next, NEXT))
            style.setShowActionsInCompactView(0, 1, 2)
        }
        notifications.notify(NOTIFICATION_ID, builder.build())
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = remote(PLAY)
        override fun onPause() = remote(PAUSE)
        override fun onSkipToNext() = remote(NEXT)
        override fun onSkipToPrevious() = remote(PREVIOUS)
    }

    /** A button: ReceiverService hands [command] to the engine (Engine.remote). */
    private fun action(icon: Int, title: Int, command: Int) = Notification.Action.Builder(
        Icon.createWithResource("android", icon),
        context.getString(title),
        PendingIntent.getService(
            context,
            command,
            Intent(context, ReceiverService::class.java).setAction(ACTION_REMOTE).putExtra(EXTRA_COMMAND, command),
            PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    /**
     * Where the track is: the position the sender last reported (-1: unknown), carried on while
     * playing. [at]: when, on SystemClock.elapsedRealtime(), as PlaybackState wants it.
     */
    class Progress {
        private var reported = -1L
        private var playing = false
        var position = -1L
            private set
        var at = 0L
            private set

        fun update(reported: Long, playing: Boolean, now: Long) {
            position = when {
                reported != this.reported -> reported // a new track, a seek, or AirPlay 2's pause
                position >= 0 && this.playing -> position + (now - at)
                else -> position
            }
            this.reported = reported
            this.playing = playing
            at = now
        }
    }

    companion object {
        /** Bundle keys besides MediaMetadata's: Shairport's play_status_type, the sender's position, AirPlay 2. */
        const val KEY_STATE = "state"
        const val KEY_POSITION = "position"
        const val KEY_REMOTE = "remote"
        /** ReceiverService intent from the notification's buttons, with [EXTRA_COMMAND]. */
        const val ACTION_REMOTE = "com.hkfuertes.shairport.REMOTE"
        const val EXTRA_COMMAND = "command"
        /** Shairport's simple_command_t (remote/remote.h). */
        const val PLAY = 0
        const val PAUSE = 1
        const val NEXT = 4
        const val PREVIOUS = 5
        private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        private const val CHANNEL_ID = "now_playing"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "Shairport"
    }
}
