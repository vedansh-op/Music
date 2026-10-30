package com.vedansh.unifiedmusic.playback

import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Hosts the Media3 player and session for foreground-service/background audio playback. */
class MusicPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Keep the default Media3 sources for local files, content URIs and ordinary HTTP.
        // Provider authentication is handled by the YouTube and Spotify integrations,
        // not by injecting obsolete Audius headers into every media request.
        val dataSourceFactory = DefaultDataSource.Factory(this)
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
