package com.vedansh.unifiedmusic.catalog

interface MusicCatalog {
    suspend fun search(query: String): List<Track>
}

/** Synchronous demo implementation used only to shape and test the first UI. */
class DemoCatalog {
    private val tracks = listOf(
        Track("demo-1", "demo", "Midnight City", "M83", source = TrackSource.DEMO),
        Track("demo-2", "demo", "Blinding Lights", "The Weeknd", source = TrackSource.DEMO),
        Track("demo-3", "demo", "A Sky Full of Stars", "Coldplay", source = TrackSource.DEMO),
        Track("demo-4", "demo", "Sunflower", "Post Malone, Swae Lee", source = TrackSource.DEMO),
        Track("demo-5", "demo", "Night Changes", "One Direction", source = TrackSource.DEMO)
    )

    fun search(query: String): List<Track> =
        if (query.isBlank()) emptyList()
        else tracks.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
        }
}
