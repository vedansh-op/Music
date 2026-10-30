package com.vedansh.unifiedmusic.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GuestYouTubeSearchPage(
    val tracks: List<StreamTrack>,
    val nextPage: Page?
)

/**
 * Public YouTube discovery and playback for guest sessions.
 *
 * It mirrors the anonymous NewPipe fallback used by Umihi Music: public search and
 * stream resolution do not require a Google account, API key, or OAuth token.
 * Account-scoped playlists and likes remain in YouTubeCatalog and still require
 * the user's explicit Google authorization.
 */
object GuestYouTubeRepository {
    private val initLock = Any()
    @Volatile private var initialized = false

    private val downloader by lazy { GuestYouTubeDownloader() }

    suspend fun search(query: String, continuation: Page? = null): GuestYouTubeSearchPage =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "Enter a song or artist to search." }
            ensureInitialized()

            val service = ServiceList.YouTube
            val queryHandler = service.searchQHFactory.fromQuery(query.trim())

            val items = if (continuation == null) {
                val info = SearchInfo.getInfo(service, queryHandler)
                GuestYouTubeSearchPage(
                    tracks = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::toTrack),
                    nextPage = info.nextPage
                )
            } else {
                val page = SearchInfo.getMoreItems(service, queryHandler, continuation)
                GuestYouTubeSearchPage(
                    tracks = page.items.filterIsInstance<StreamInfoItem>().mapNotNull(::toTrack),
                    nextPage = page.nextPage
                )
            }
            items
        }

    suspend fun resolveAudioStream(videoId: String): String = withContext(Dispatchers.IO) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "This YouTube result has an invalid video ID." }
        ensureInitialized()

        val videoUrl = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl)
        extractor.fetchPage()

        extractor.audioStreams
            .asSequence()
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?.content
            ?: throw IOException("YouTube did not provide a playable audio stream for this video.")
    }

    private fun toTrack(item: StreamInfoItem): StreamTrack? {
        val videoId = videoIdFromUrl(item.url) ?: return null
        val thumbnail = item.thumbnails.lastOrNull()?.url?.takeIf { it.isNotBlank() }
        val duration = item.duration.takeIf { it > 0L }?.times(1_000L)

        return StreamTrack(
            id = "youtube:$videoId",
            title = item.name.ifBlank { "Unknown video" },
            artist = item.uploaderName.ifBlank { "YouTube" },
            artworkUrl = thumbnail,
            provider = StreamProvider.YOUTUBE,
            playbackId = videoId,
            webUrl = item.url,
            durationMs = duration
        )
    }

    private fun videoIdFromUrl(url: String): String? {
        val uri = android.net.Uri.parse(url)
        return (uri.getQueryParameter("v") ?: uri.lastPathSegment)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            NewPipe.init(downloader)
            initialized = true
        }
    }

    private class GuestYouTubeDownloader : Downloader() {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        override fun execute(request: Request): Response {
            val requestBody = request.dataToSend()?.let { bytes ->
                okhttp3.RequestBody.create(null, bytes)
            }
            val builder = OkHttpRequest.Builder()
                .url(request.url())
                .method(request.httpMethod(), requestBody)

            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { value -> builder.addHeader(name, value) }
            }

            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    body,
                    response.request.url.toString()
                )
            }
        }
    }
}
