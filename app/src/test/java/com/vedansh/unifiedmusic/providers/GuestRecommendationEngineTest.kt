package com.vedansh.unifiedmusic.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestRecommendationEngineTest {
    @Test
    fun newGuestGetsUsefulStarterSuggestions() {
        val suggestions = GuestRecommendationEngine.recommend(emptyList(), nowMillis = 1_000_000L)
        assertEquals(6, suggestions.size)
        assertTrue(suggestions.any { it.title == "Hindi hits" })
        assertTrue(suggestions.any { it.title == "Lo-fi & chill" })
    }

    @Test
    fun repeatedRecentListeningMovesArtistIntoSuggestions() {
        val now = 50_000_000L
        val signals = listOf(
            GuestTasteSignal("play", artist = "Old Artist", timestampMillis = now - 40L * 24 * 60 * 60 * 1000),
            GuestTasteSignal("play", artist = "New Artist", timestampMillis = now - 60_000L),
            GuestTasteSignal("play", artist = "New Artist", timestampMillis = now - 30_000L)
        )

        val first = GuestRecommendationEngine.recommend(signals, nowMillis = now).first()
        assertEquals("New Artist mix", first.title)
        assertEquals("New Artist", first.query)
    }

    @Test
    fun recentSearchesAreUsedWhenThereIsNoPlayHistory() {
        val now = 80_000_000L
        val suggestions = GuestRecommendationEngine.recommend(
            listOf(GuestTasteSignal("search", query = "Arijit Singh", timestampMillis = now - 1_000L)),
            nowMillis = now
        )

        assertEquals("More like Arijit Singh", suggestions.first().title)
        assertEquals("Arijit Singh", suggestions.first().query)
    }

    @Test
    fun duplicateSuggestionsAreRemoved() {
        val now = 90_000_000L
        val suggestions = GuestRecommendationEngine.recommend(
            listOf(
                GuestTasteSignal("search", query = "Punjabi Hits", timestampMillis = now),
                GuestTasteSignal("search", query = "punjabi hits", timestampMillis = now - 1_000L)
            ),
            nowMillis = now
        )
        assertEquals(1, suggestions.count { it.query.equals("Punjabi Hits", ignoreCase = true) })
    }
}
