package com.vedansh.unifiedmusic.providers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

data class GuestTasteSignal(
    val kind: String,
    val query: String = "",
    val title: String = "",
    val artist: String = "",
    val timestampMillis: Long
)

data class GuestSuggestion(
    val title: String,
    val query: String,
    val reason: String
)

/**
 * Small on-device recommendation engine. Recent searches and plays have a decaying
 * weight, while likes (when added by the UI) can carry a stronger signal. No account
 * or network service is needed to build these suggestions.
 */
object GuestRecommendationEngine {
    private const val DAY_MS = 24.0 * 60.0 * 60.0 * 1000.0
    private val starterSuggestions = listOf(
        GuestSuggestion("Hindi hits", "Hindi hit songs", "A good place to start"),
        GuestSuggestion("Punjabi mix", "Punjabi hit songs", "Popular Punjabi tracks"),
        GuestSuggestion("Lo-fi & chill", "lofi chill music", "For slower moments"),
        GuestSuggestion("Workout energy", "high energy workout songs", "Turn the energy up"),
        GuestSuggestion("Gaming mixes", "gaming music mix", "Soundtracks for game time"),
        GuestSuggestion("Focus music", "focus study music", "Music for getting things done")
    )

    fun recommend(
        signals: List<GuestTasteSignal>,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 6
    ): List<GuestSuggestion> {
        if (limit <= 0) return emptyList()

        val artistScores = linkedMapOf<String, Double>()
        val queryScores = linkedMapOf<String, Double>()
        val artistLabels = linkedMapOf<String, String>()
        val queryLabels = linkedMapOf<String, String>()

        signals.forEach { signal ->
            val ageDays = ((nowMillis - signal.timestampMillis).coerceAtLeast(0L)) / DAY_MS
            val recency = exp(-ageDays / 21.0)
            when (signal.kind) {
                "play", "like" -> {
                    val artist = signal.artist.trim()
                    if (artist.isNotBlank()) {
                        val key = artist.lowercase()
                        val weight = if (signal.kind == "like") 4.0 else 2.2
                        artistScores[key] = (artistScores[key] ?: 0.0) + weight * recency
                        artistLabels.putIfAbsent(key, artist)
                    }
                }
                "search" -> {
                    val query = signal.query.trim()
                    if (query.isNotBlank()) {
                        val key = query.lowercase()
                        queryScores[key] = (queryScores[key] ?: 0.0) + recency
                        queryLabels.putIfAbsent(key, query)
                    }
                }
            }
        }

        val suggestions = mutableListOf<GuestSuggestion>()
        artistScores.entries
            .sortedByDescending { it.value }
            .forEach { (key, _) ->
                val artist = artistLabels[key] ?: return@forEach
                suggestions += GuestSuggestion(
                    title = "$artist mix",
                    query = artist,
                    reason = "Based on artists you've played"
                )
            }
        queryScores.entries
            .sortedByDescending { it.value }
            .forEach { (key, _) ->
                val query = queryLabels[key] ?: return@forEach
                suggestions += GuestSuggestion(
                    title = "More like $query",
                    query = query,
                    reason = "From your recent searches"
                )
            }

        val unique = suggestions.distinctBy { it.query.trim().lowercase() }
        return (if (unique.isEmpty()) starterSuggestions else unique + starterSuggestions)
            .distinctBy { it.query.trim().lowercase() }
            .take(limit)
    }
}

/** Stores only lightweight listening signals locally on this device. */
class GuestTasteRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun recordSearch(query: String) {
        val cleanQuery = query.trim().take(120)
        if (cleanQuery.isBlank()) return
        append(GuestTasteSignal("search", query = cleanQuery, timestampMillis = System.currentTimeMillis()))
    }

    @Synchronized
    fun recordPlay(track: StreamTrack) {
        recordPlay(track.title, track.artist)
    }

    @Synchronized
    fun recordLocalPlay(title: String, artist: String) {
        recordPlay(title, artist)
    }

    @Synchronized
    fun recordLike(title: String, artist: String) {
        if (title.isBlank() && artist.isBlank()) return
        append(
            GuestTasteSignal(
                kind = "like",
                title = title.take(160),
                artist = artist.take(120),
                timestampMillis = System.currentTimeMillis()
            )
        )
    }

    @Synchronized
    private fun recordPlay(title: String, artist: String) {
        append(
            GuestTasteSignal(
                kind = "play",
                title = title.take(160),
                artist = artist.take(120),
                timestampMillis = System.currentTimeMillis()
            )
        )
    }

    fun suggestions(): List<GuestSuggestion> = GuestRecommendationEngine.recommend(readSignals())

    @Synchronized
    private fun append(signal: GuestTasteSignal) {
        val signals = (readSignals() + signal).takeLast(MAX_SIGNALS)
        val array = JSONArray()
        signals.forEach { item ->
            array.put(
                JSONObject()
                    .put("kind", item.kind)
                    .put("query", item.query)
                    .put("title", item.title)
                    .put("artist", item.artist)
                    .put("timestamp", item.timestampMillis)
            )
        }
        preferences.edit().putString(KEY_SIGNALS, array.toString()).apply()
    }

    private fun readSignals(): List<GuestTasteSignal> {
        val raw = preferences.getString(KEY_SIGNALS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val kind = item.optString("kind")
                    if (kind !in setOf("search", "play", "like")) continue
                    add(
                        GuestTasteSignal(
                            kind = kind,
                            query = item.optString("query"),
                            title = item.optString("title"),
                            artist = item.optString("artist"),
                            timestampMillis = item.optLong("timestamp", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFERENCES = "guest_taste"
        const val KEY_SIGNALS = "signals"
        const val MAX_SIGNALS = 80
    }
}
