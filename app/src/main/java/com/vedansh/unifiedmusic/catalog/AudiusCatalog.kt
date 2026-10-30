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
                        AudiusTrackMapper.fromJson(item, base, APP_NAME)?.let(::add)
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
