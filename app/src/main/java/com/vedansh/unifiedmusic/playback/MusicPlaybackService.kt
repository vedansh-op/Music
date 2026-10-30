package com.vedansh.unifiedmusic.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vedansh.unifiedmusic.providers.GuestYouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Hosts local playback and login-free YouTube Music audio playback in a media session. */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val baseFactory = DefaultDataSource.Factory(this)
        val resolvingFactory = ResolvingDataSource.Factory(baseFactory) { dataSpec ->
            val uri = dataSpec.uri
            val videoId = youtubeVideoId(uri)
            if (videoId == null) {
                dataSpec
            } else {
                // Media3 calls the resolver off the UI thread. Stream resolution is
                // blocking at the DataSource boundary but runs on IO, just like the
                // Resolver pattern used by the Umihi player.
                val streamUrl = runBlocking(Dispatchers.IO) {
                    GuestYouTubeRepository.resolveAudioStream(videoId)
                }
                dataSpec.withUri(Uri.parse(streamUrl))
            }
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(resolvingFactory)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    private fun youtubeVideoId(uri: Uri): String? {
        val host = uri.host.orEmpty()
        if (!host.endsWith("youtube.com") && !host.endsWith("youtu.be")) return null
        val id = if (host.endsWith("youtu.be")) uri.lastPathSegment else uri.getQueryParameter("v")
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
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
