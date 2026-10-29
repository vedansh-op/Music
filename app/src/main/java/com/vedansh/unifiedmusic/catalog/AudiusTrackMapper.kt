package com.vedansh.unifiedmusic.catalog

import org.json.JSONObject
import java.net.URLEncoder

/** Converts a provider search result into a Track without any network dependency. */
internal object AudiusTrackMapper {
    fun fromJson(track: JSONObject, streamBase: String, appName: String): Track? {
        if (!isStreamable(track)) return null

        val providerId = track.optString("id", "").trim()
        val title = track.optString("title", "").trim()
        if (providerId.isEmpty() || title.isEmpty()) return null

        val user = track.optJSONObject("user")
        val artist = user?.optString("name", "")?.takeIf { it.isNotBlank() }
            ?: user?.optString("handle", "")?.takeIf { it.isNotBlank() }
            ?: "Audius artist"

        val artwork = track.optJSONObject("artwork")
        val artworkUrl = artwork?.optString("_480x480", "")?.takeIf { it.startsWith("https://") }
            ?: artwork?.optString("_1000x1000", "")?.takeIf { it.startsWith("https://") }
            ?: artwork?.optString("_150x150", "")?.takeIf { it.startsWith("https://") }

        val durationSeconds = track.optDouble("duration", 0.0)
        val encodedId = URLEncoder.encode(providerId, Charsets.UTF_8.name())
        val streamUrl = streamBase.trimEnd('/') + "/tracks/" + encodedId + "/stream?app_name=" + appName
        val sourceUrl = track.optString("permalink", "").takeIf { it.startsWith("https://") }

        return Track(
            id = "audius:$providerId",
            providerId = providerId,
            title = title,
            artist = artist,
            artworkUrl = artworkUrl,
            streamUrl = streamUrl,
            sourceUrl = sourceUrl,
            durationMs = (durationSeconds * 1_000.0).toLong().takeIf { it > 0L },
            source = TrackSource.AUDIUS,
            availability = TrackAvailability.AVAILABLE
        )
    }

    private fun isStreamable(track: JSONObject): Boolean {
        val value = when {
            track.has("isStreamable") -> track.opt("isStreamable")
            track.has("is_streamable") -> track.opt("is_streamable")
            else -> null
        } ?: return true

        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            JSONObject.NULL -> true
            else -> true
        }
    }
}
