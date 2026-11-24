package com.amiralicr.waterplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import org.json.JSONObject

class WaterPlaybackService : Service() {
    private lateinit var session: MediaSessionCompat
    private var title = "واتر پلیر"
    private var artist = "آماده پخش"
    private var album = ""
    private var artwork: String? = null
    private var duration = 0L
    private var position = 0L
    private var playing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "WaterPlayerSession")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { WaterPlayerActivity.dispatchMediaAction("play") }
            override fun onPause() { WaterPlayerActivity.dispatchMediaAction("pause") }
            override fun onSkipToNext() { WaterPlayerActivity.dispatchMediaAction("next") }
            override fun onSkipToPrevious() { WaterPlayerActivity.dispatchMediaAction("previous") }
            override fun onSeekTo(pos: Long) { WaterPlayerActivity.dispatchMediaAction("seek", pos) }
        })
        session.isActive = true
        updateSessionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "media-play" -> WaterPlayerActivity.dispatchMediaAction("play")
            "media-pause" -> WaterPlayerActivity.dispatchMediaAction("pause")
            "media-next" -> WaterPlayerActivity.dispatchMediaAction("next")
            "media-previous" -> WaterPlayerActivity.dispatchMediaAction("previous")
            ACTION_METADATA -> {
                runCatching {
                    val data = JSONObject(intent.getStringExtra(EXTRA_METADATA) ?: "{}")
                    title = data.optString("title", title)
                    artist = data.optString("artist", artist)
                    album = data.optString("album", album)
                    artwork = data.optString("artwork", "").takeIf { it.isNotBlank() }
                    duration = data.optLong("duration", duration)
                }
                updateMetadata()
                if (playing) showForeground()
            }
            ACTION_PLAY -> {
                playing = true
                position = intent.getLongExtra(EXTRA_POSITION, position)
                updateSessionState()
                showForeground()
            }
            ACTION_PAUSE -> {
                playing = false
                position = intent.getLongExtra(EXTRA_POSITION, position)
                updateSessionState()
                showForeground()
            }
            ACTION_POSITION -> {
                position = intent.getLongExtra(EXTRA_POSITION, position)
                duration = intent.getLongExtra(EXTRA_DURATION, duration)
                updateSessionState()
            }
            ACTION_STOP -> {
                playing = false
                updateSessionState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun updateMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        artwork?.let { uri ->
            runCatching {
                contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        }
        session.setMetadata(builder.build())
        updateSessionState()
    }

    private fun updateSessionState() {
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackStateCompat.Builder().setActions(actions)
                .setState(state, position, if (playing) 1f else 0f).build()
        )
    }

    private fun showForeground() {
        val openIntent = PendingIntent.getActivity(
            this, 10, Intent(this, WaterPlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val previous = actionPending("previous", 11)
        val playPause = actionPending(if (playing) "pause" else "play", 12)
        val next = actionPending("next", 13)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(artist).setSubText("واتر پلیر")
            .setContentIntent(openIntent).setOnlyAlertOnce(true).setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "قبلی", previous)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "مکث" else "پخش", playPause
            )
            .addAction(android.R.drawable.ic_media_next, "بعدی", next)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun actionPending(action: String, request: Int): PendingIntent =
        PendingIntent.getService(
            this, request,
            Intent(this, WaterPlaybackService::class.java).setAction("media-$action"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "پخش موسیقی", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "water_player_media"
        const val NOTIFICATION_ID = 714
        const val EXTRA_METADATA = "metadata"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val ACTION_METADATA = "water.metadata"
        const val ACTION_PLAY = "water.play"
        const val ACTION_PAUSE = "water.pause"
        const val ACTION_POSITION = "water.position"
        const val ACTION_STOP = "water.stop"
    }
}
