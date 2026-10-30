package com.vedansh.unifiedmusic.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object YouTubeCatalog {
    suspend fun search(query: String, accessToken: String, apiKey: String): List<StreamTrack> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "Enter a song or artist to search." }
            if (accessToken.isBlank() && apiKey.isBlank()) {
                throw IOException("Connect Google and add a YouTube Data API key to search YouTube.")
            }
            val params = buildString {
                append("part=snippet&type=video&videoCategoryId=10&videoEmbeddable=true&maxResults=25")
                append("&q=").append(URLEncoder.encode(query.trim(), Charsets.UTF_8.name()))
                if (apiKey.isNotBlank()) append("&key=").append(URLEncoder.encode(apiKey, Charsets.UTF_8.name()))
            }
            val connection = URL("https://www.googleapis.com/youtube/v3/search?$params")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (accessToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IOException("YouTube search failed (HTTP $status): $body")
                }
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
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
            } finally {
                connection.disconnect()
            }
        }
}
