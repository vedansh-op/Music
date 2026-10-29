package com.vedansh.unifiedmusic.playback

import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vedansh.unifiedmusic.BuildConfig

/** Hosts the Media3 player and session for foreground-service/background audio playback. */
class MusicPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        if (BuildConfig.AUDIUS_API_KEY.isNotBlank()) {
            httpDataSourceFactory.setDefaultRequestProperties(mapOf("x-api-key" to BuildConfig.AUDIUS_API_KEY))
        }
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val exoPlayer = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
