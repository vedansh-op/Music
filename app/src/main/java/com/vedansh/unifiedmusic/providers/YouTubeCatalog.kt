package com.vedansh.unifiedmusic.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class YouTubePlaylist(
    val id: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val itemCount: Int
)

object YouTubeCatalog {
    /**
     * Validates the granted YouTube scope against a real, account-scoped Data API request.
     * This prevents the UI from showing "Connected" merely because Google returned a token.
     */
    suspend fun getAuthenticatedChannel(accessToken: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            require(accessToken.isNotBlank()) { "Google did not return an access token." }
            val root = getJson(
                url = withApiKey(
                    "https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true&maxResults=1",
                    apiKey
                ),
                accessToken = accessToken
            )
            val items = root.optJSONArray("items") ?: return@withContext null
            val channel = items.optJSONObject(0) ?: return@withContext null
            channel.optJSONObject("snippet")?.optString("title")?.takeIf { it.isNotBlank() }
        }

    /** Reads the signed-in user's YouTube playlists using the official Data API. */
    suspend fun getMyPlaylists(accessToken: String, apiKey: String): List<YouTubePlaylist> =
        withContext(Dispatchers.IO) {
            require(accessToken.isNotBlank()) { "Connect Google before loading your YouTube playlists." }
            val root = getJson(
                url = withApiKey(
                    "https://www.googleapis.com/youtube/v3/playlists?part=snippet,contentDetails&mine=true&maxResults=50",
                    apiKey
                ),
                accessToken = accessToken
            )
            val items = root.optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val snippet = item.optJSONObject("snippet") ?: continue
                    val title = snippet.optString("title")
                    if (id.isBlank() || title.isBlank()) continue
                    val thumbnails = snippet.optJSONObject("thumbnails")
                    val thumbnail = thumbnails?.optJSONObject("high")?.optString("url")
                        ?: thumbnails?.optJSONObject("medium")?.optString("url")
                        ?: thumbnails?.optJSONObject("default")?.optString("url")
                    val count = item.optJSONObject("contentDetails")?.optInt("itemCount", 0) ?: 0
                    add(
                        YouTubePlaylist(
                            id = id,
                            title = title,
                            description = snippet.optString("description"),
                            thumbnailUrl = thumbnail?.takeIf { it.isNotBlank() },
                            itemCount = count
                        )
                    )
                }
            }
        }

    /** Reads the selected playlist's videos; pagination can be added without changing this model. */
    suspend fun getPlaylistTracks(
        playlistId: String,
        accessToken: String,
        apiKey: String
    ): List<StreamTrack> = withContext(Dispatchers.IO) {
        require(playlistId.isNotBlank()) { "Choose a YouTube playlist first." }
        require(accessToken.isNotBlank()) { "Connect Google before opening a YouTube playlist." }
        val root = getJson(
            url = withApiKey(
                "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&playlistId=${encode(playlistId)}&maxResults=50",
                apiKey
            ),
            accessToken = accessToken
        )
        val items = root.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val snippet = item.optJSONObject("snippet") ?: continue
                val videoId = snippet.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                if (videoId.isBlank()) continue
                val thumbnails = snippet.optJSONObject("thumbnails")
                val image = thumbnails?.optJSONObject("high")?.optString("url")
                    ?: thumbnails?.optJSONObject("medium")?.optString("url")
                add(
                    StreamTrack(
                        id = "youtube:$videoId",
                        title = snippet.optString("title", "Unavailable video"),
                        artist = snippet.optString("videoOwnerChannelTitle", "YouTube"),
                        artworkUrl = image?.takeIf { it.isNotBlank() },
                        provider = StreamProvider.YOUTUBE,
                        playbackId = videoId,
                        webUrl = "https://www.youtube.com/watch?v=$videoId"
                    )
                )
            }
        }
    }

    suspend fun search(query: String, accessToken: String, apiKey: String): List<StreamTrack> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "Enter a song or artist to search." }
            if (accessToken.isBlank() && apiKey.isBlank()) {
                throw IOException("Connect Google and configure YouTube Data API v3 before searching.")
            }
            val params = buildString {
                append("part=snippet&type=video&videoEmbeddable=true&maxResults=25")
                append("&order=relevance&regionCode=IN&relevanceLanguage=en")
                append("&q=").append(encode(query.trim()))
            }
            val root = getJson(
                url = withApiKey("https://www.googleapis.com/youtube/v3/search?$params", apiKey),
                accessToken = accessToken
            )
            val items = root.optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val videoId = item.optJSONObject("id")?.optString("videoId").orEmpty()
                    if (videoId.isBlank()) continue
                    val snippet = item.optJSONObject("snippet") ?: continue
                    val thumbnails = snippet.optJSONObject("thumbnails")
                    val image = thumbnails?.optJSONObject("high")?.optString("url")
                        ?: thumbnails?.optJSONObject("medium")?.optString("url")
                    add(
                        StreamTrack(
                            id = "youtube:$videoId",
                            title = snippet.optString("title", "Unknown video"),
                            artist = snippet.optString("channelTitle", "YouTube"),
                            artworkUrl = image?.takeIf { it.isNotBlank() },
                            provider = StreamProvider.YOUTUBE,
                            playbackId = videoId,
                            webUrl = "https://www.youtube.com/watch?v=$videoId"
                        )
                    )
                }
            }
        }

    private fun getJson(url: String, accessToken: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        if (accessToken.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
        }
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw describeApiError(status, body)
            }
            if (body.isBlank()) throw IOException("YouTube returned an empty response.")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun describeApiError(status: Int, body: String): IOException {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
        val reason = error?.optJSONArray("errors")
            ?.optJSONObject(0)
            ?.optString("reason")
            ?.takeIf { it.isNotBlank() }
        val hint = when (reason) {
            "accessNotConfigured", "SERVICE_DISABLED" ->
                "Enable YouTube Data API v3 in the Google Cloud project used by this Android OAuth client."
            "quotaExceeded", "dailyLimitExceeded" ->
                "The YouTube API quota is exhausted. The default search.list allowance is limited; wait for reset or request more quota."
            "insufficientPermissions", "authError" ->
                "Google returned a token without the required YouTube permission. Disconnect and authorize again."
            "keyInvalid", "invalid" ->
                "The configured YouTube API key is invalid. Remove the bad key or replace it in the build configuration."
            else -> null
        }
        return IOException(
            buildString {
                append("YouTube API request failed (HTTP $status)")
                reason?.let { append(", reason: ").append(it) }
                append(". ")
                append(hint ?: message ?: body.take(400).ifBlank { "No error details were returned." })
            }
        )
    }

    private fun withApiKey(url: String, apiKey: String): String =
        if (apiKey.isBlank()) url else "$url&key=${encode(apiKey)}"

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
