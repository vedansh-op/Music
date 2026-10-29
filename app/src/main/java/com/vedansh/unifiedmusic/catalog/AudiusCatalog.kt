package com.vedansh.unifiedmusic.catalog

import com.vedansh.unifiedmusic.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Audius track search/stream adapter using the provider's REST endpoints. */
class AudiusCatalog : MusicCatalog {
    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val encodedQuery = URLEncoder.encode(cleanQuery, Charsets.UTF_8.name())
        val bases = if (BuildConfig.AUDIUS_API_KEY.isNotBlank()) {
            listOf(API_BASE, DISCOVERY_BASE)
        } else {
            listOf(DISCOVERY_BASE, API_BASE)
        }

        var lastFailure: Exception? = null
        for (base in bases) {
            try {
                val url = "$base/tracks/search?query=$encodedQuery&limit=$RESULT_LIMIT&app_name=$APP_NAME"
                val response = getJson(url)
                val data = response.optJSONArray("data")
                    ?: throw IOException("Audius returned an unexpected search response")
                return@withContext buildList {
                    for (index in 0 until data.length()) {
                        val item = data.optJSONObject(index) ?: continue
                        if (!isStreamable(item)) continue
                        val providerId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                        val user = item.optJSONObject("user")
                        val artist = user?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: user?.optString("handle")?.takeIf { it.isNotBlank() }
                            ?: "Audius artist"
                        val artwork = item.optJSONObject("artwork")
                        val artworkUrl = artwork?.optString("_480x480")?.takeIf { it.startsWith("https://") }
                            ?: artwork?.optString("_1000x1000")?.takeIf { it.startsWith("https://") }
                            ?: artwork?.optString("_150x150")?.takeIf { it.startsWith("https://") }
                        val durationSeconds = item.optDouble("duration", 0.0)
                        val encodedId = URLEncoder.encode(providerId, Charsets.UTF_8.name())
                        val streamUrl = base + "/tracks/" + encodedId + "/stream?app_name=" + APP_NAME
                        add(
                            Track(
                                id = "audius:$providerId",
                                providerId = providerId,
                                title = title,
                                artist = artist,
                                artworkUrl = artworkUrl,
                                streamUrl = streamUrl,
                                durationMs = (durationSeconds * 1_000.0).toLong().takeIf { it > 0L },
                                source = TrackSource.AUDIUS,
                                availability = TrackAvailability.AVAILABLE
                            )
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                lastFailure = exception
            }
        }
        throw IOException("Audius search is currently unavailable. Check your connection and try again.", lastFailure)
    }

    private fun isStreamable(track: JSONObject): Boolean {
        val value = when {
            track.has("isStreamable") -> track.opt("isStreamable")
            track.has("is_streamable") -> track.opt("is_streamable")
            else -> null
        } ?: return true

        return when (value) {
            is Boolean -> value
            is String -> !value.equals("false", ignoreCase = true) && value.isNotBlank()
            JSONObject.NULL -> true
            else -> true
        }
    }

    private fun getJson(requestUrl: String): JSONObject {
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "UnifiedMusic/0.4.0 Android")
        if (BuildConfig.AUDIUS_API_KEY.isNotBlank()) {
            connection.setRequestProperty("x-api-key", BuildConfig.AUDIUS_API_KEY)
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) throw IOException("Audius returned HTTP $statusCode")
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val APP_NAME = "UnifiedMusic"
        const val RESULT_LIMIT = 25
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 18_000
        const val API_BASE = "https://api.audius.co/v1"
        const val DISCOVERY_BASE = "https://discoveryprovider.audius.co/v1"
    }
}
