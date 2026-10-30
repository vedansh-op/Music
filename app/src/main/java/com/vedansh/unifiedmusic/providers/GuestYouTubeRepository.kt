package com.vedansh.unifiedmusic.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GuestYouTubeSearchPage(
    val tracks: List<StreamTrack>,
    val nextPageToken: String?
)

/**
 * YouTube Music guest catalogue.
 *
 * Searches the public YouTube Music music shelf without a Google account. Private
 * account features remain in YouTubeCatalog and are never called from guest search.
 * Stream resolution uses NewPipe Extractor as the playback fallback.
 */
object GuestYouTubeRepository {
    private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val CLIENT_VERSION = "1.20261018.16.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private const val SEARCH_FILTER = "EgWKAQIIAWoSEAMQBBAQEAUQFRAKEAkQERAO"
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

    private val initLock = Any()
    @Volatile private var initialized = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun searchPage(query: String, continuation: String? = null): GuestYouTubeSearchPage =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank() || !continuation.isNullOrBlank()) {
                "Enter a song, artist or album to search."
            }
            val body = JSONObject().put("context", buildContext())
            if (!continuation.isNullOrBlank()) {
                body.put("continuation", continuation)
            } else {
                body.put("query", query.trim())
                body.put("params", SEARCH_FILTER)
            }

            val json = postJson(SEARCH_URL, body)
            GuestYouTubeSearchPage(
                tracks = parseSearchTracks(json),
                nextPageToken = findContinuation(json)
            )
        }

    suspend fun resolveAudioStream(videoId: String): String = withContext(Dispatchers.IO) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            "This YouTube Music result has an invalid video ID."
        }
        ensureNewPipeInitialized()

        // Try the public YouTube Music player response first, without a Google login.
        val playerResponse = runCatching {
            postJson(
                PLAYER_URL,
                JSONObject()
                    .put("context", buildContext())
                    .put("videoId", videoId)
                    .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject()))
            )
        }.getOrNull()

        val directUrl = playerResponse?.let(::bestAdaptiveAudioUrl)
        if (!directUrl.isNullOrBlank()) return@withContext directUrl

        // Fallback matching Umihi's public guest playback route.
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        extractor.audioStreams
            .asSequence()
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?.content
            ?: throw IOException("YouTube Music couldn't provide an audio stream for this track.")
    }

    private fun buildContext(): JSONObject = JSONObject()
        .put(
            "client",
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", CLIENT_VERSION)
                .put("xClientName", "67")
                .put("userAgent", USER_AGENT)
                .put("platform", "DESKTOP")
        )
        .put("user", JSONObject().put("lockedSafetyMode", false))

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val request = OkHttpRequest.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .header("Content-Type", JSON_MEDIA_TYPE)
            .header("Accept", "application/json")
            .header("Origin", MUSIC_ORIGIN)
            .header("Referer", "$MUSIC_ORIGIN/")
            .header("X-Origin", MUSIC_ORIGIN)
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("X-YouTube-Client-Name", "67")
            .header("User-Agent", USER_AGENT)
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("YouTube Music search failed (HTTP ${response.code}): ${raw.take(350)}")
            }
            if (raw.isBlank()) throw IOException("YouTube Music returned an empty response.")
            return JSONObject(raw)
        }
    }

    private fun parseSearchTracks(root: JSONObject): List<StreamTrack> {
        val shelf = findObject(root, "musicShelfRenderer")
            ?: findObject(root, "musicShelfContinuation")
            ?: return emptyList()
        val contents = shelf.optJSONArray("contents") ?: return emptyList()
        val tracks = linkedMapOf<String, StreamTrack>()
        for (index in 0 until contents.length()) {
            val item = contents.optJSONObject(index) ?: continue
            val renderer = findObject(item, "musicResponsiveListItemRenderer") ?: continue
            val track = parseTrack(renderer) ?: continue
            tracks.putIfAbsent(track.id, track)
        }
        return tracks.values.toList()
    }

    private fun parseTrack(renderer: JSONObject): StreamTrack? {
        val videoId = findString(renderer, "videoId")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            ?: return null

        val columns = renderer.optJSONArray("flexColumns")
        val title = columns?.optJSONObject(0)
            ?.let { findString(it, "text") ?: findString(it, "simpleText") }
            ?.takeIf { it.isNotBlank() }
            ?: findString(renderer, "title")
            ?: "Unknown track"

        val artistText = columns?.optJSONObject(1)
            ?.let { findString(it, "text") ?: findString(it, "simpleText") }
            .orEmpty()
        val artist = artistText
            .split(" • ")
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: "YouTube Music"

        val duration = renderer.optJSONArray("fixedColumns")
            ?.optJSONObject(0)
            ?.let { findString(it, "text") }
            ?.let(::durationMillis)

        val thumbnail = findBestThumbnail(renderer)
        return StreamTrack(
            id = "youtube:$videoId",
            title = title,
            artist = artist,
            artworkUrl = thumbnail,
            provider = StreamProvider.YOUTUBE,
            playbackId = videoId,
            webUrl = "https://music.youtube.com/watch?v=$videoId",
            durationMs = duration
        )
    }

    private fun durationMillis(value: String): Long? {
        val parts = value.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return null
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return seconds.takeIf { it > 0 }?.times(1000L)
    }

    private fun findContinuation(root: JSONObject): String? {
        val next = findObject(root, "nextContinuationData") ?: return null
        return next.optString("continuation").takeIf { it.isNotBlank() }
    }

    private fun bestAdaptiveAudioUrl(root: JSONObject): String? {
        val streaming = root.optJSONObject("streamingData") ?: return null
        val formats = streaming.optJSONArray("adaptiveFormats") ?: return null
        var bestUrl: String? = null
        var bestBitrate = -1
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val mime = format.optString("mimeType")
            val url = format.optString("url")
            if (!mime.startsWith("audio/") || url.isBlank()) continue
            val bitrate = format.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun findBestThumbnail(root: JSONObject): String? {
        val thumbnails = findObject(root, "thumbnails") ?: return null
        val urls = mutableListOf<String>()
        collectUrls(thumbnails, urls)
        return urls.lastOrNull { it.startsWith("https://") }
    }

    private fun collectUrls(value: Any?, output: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                val url = value.optString("url")
                if (url.startsWith("https://")) output += url
                val keys = value.keys()
                while (keys.hasNext()) collectUrls(value.opt(keys.next()), output)
            }
            is JSONArray -> for (i in 0 until value.length()) collectUrls(value.opt(i), output)
        }
    }

    private fun findObject(value: JSONObject, key: String): JSONObject? {
        value.optJSONObject(key)?.let { return it }
        val keys = value.keys()
        while (keys.hasNext()) {
            val child = value.opt(keys.next())
            if (child is JSONObject) findObject(child, key)?.let { return it }
            if (child is JSONArray) {
                for (index in 0 until child.length()) {
                    val item = child.optJSONObject(index) ?: continue
                    findObject(item, key)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findString(value: JSONObject, key: String): String? {
        val direct = value.opt(key)
        if (direct is String && direct.isNotBlank()) return direct
        if (direct is JSONObject) {
            direct.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
            val runs = direct.optJSONArray("runs")
            if (runs != null) {
                val text = buildString {
                    for (index in 0 until runs.length()) {
                        append(runs.optJSONObject(index)?.optString("text").orEmpty())
                    }
                }
                if (text.isNotBlank()) return text
            }
        }
        val keys = value.keys()
        while (keys.hasNext()) {
            val child = value.opt(keys.next())
            if (child is JSONObject) findString(child, key)?.let { return it }
            if (child is JSONArray) {
                for (index in 0 until child.length()) {
                    val item = child.optJSONObject(index) ?: continue
                    findString(item, key)?.let { return it }
                }
            }
        }
        return null
    }

    private fun ensureNewPipeInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            NewPipe.init(GuestYouTubeDownloader())
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
            val body = request.dataToSend()?.toRequestBody(null)
            val builder = OkHttpRequest.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)

            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { headerValue -> builder.addHeader(name, headerValue) }
            }

            client.newCall(builder.build()).execute().use { response ->
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string().orEmpty(),
                    response.request.url.toString()
                )
            }
        }
    }
}
