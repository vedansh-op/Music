package com.vedansh.unifiedmusic.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiusTrackMapperTest {
    @Test
    fun mapsPlayableTrackAndKeepsAttributionLink() {
        val json = JSONObject(
            """
            {
              "id": "track-42",
              "title": "Night Drive",
              "duration": 183.4,
              "is_streamable": true,
              "permalink": "https://audius.co/artist/night-drive",
              "user": { "name": "Test Artist", "handle": "testartist" },
              "artwork": { "_480x480": "https://cdn.example/artwork.jpg" }
            }
            """.trimIndent()
        )

        val track = AudiusTrackMapper.fromJson(json, "https://api.audius.co/v1", "UnifiedMusic")
        assertNotNull(track)
        requireNotNull(track)
        assertEquals("audius:track-42", track.id)
        assertEquals("Night Drive", track.title)
        assertEquals("Test Artist", track.artist)
        assertEquals(183_400L, track.durationMs)
        assertEquals("https://cdn.example/artwork.jpg", track.artworkUrl)
        assertEquals("https://audius.co/artist/night-drive", track.sourceUrl)
        assertEquals("https://api.audius.co/v1/tracks/track-42/stream?app_name=UnifiedMusic", track.streamUrl)
        assertEquals(TrackSource.AUDIUS, track.source)
        assertTrue(track.availability == TrackAvailability.AVAILABLE)
    }

    @Test
    fun rejectsExplicitlyNonStreamableTrack() {
        val json = JSONObject("""{"id":"track-1","title":"No Stream","is_streamable":"false"}""")
        assertNull(AudiusTrackMapper.fromJson(json, "https://api.audius.co/v1", "UnifiedMusic"))
    }

    @Test
    fun rejectsTrackWithoutRequiredIdOrTitle() {
        assertNull(AudiusTrackMapper.fromJson(JSONObject("""{"title":"No ID"}"""), "https://api.audius.co/v1", "UnifiedMusic"))
        assertNull(AudiusTrackMapper.fromJson(JSONObject("""{"id":"no-title"}"""), "https://api.audius.co/v1", "UnifiedMusic"))
    }

    @Test
    fun usesArtistHandleWhenNameAndOptionalFieldsAreMissing() {
        val json = JSONObject("""{"id":"track-3","title":"Instrumental","user":{"handle":"producer"}}""")
        val track = AudiusTrackMapper.fromJson(json, "https://discoveryprovider.audius.co/v1", "UnifiedMusic")
        assertNotNull(track)
        requireNotNull(track)
        assertEquals("producer", track.artist)
        assertNull(track.artworkUrl)
        assertNull(track.sourceUrl)
        assertNull(track.durationMs)
    }
}
