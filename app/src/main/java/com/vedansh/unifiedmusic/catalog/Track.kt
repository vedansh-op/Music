package com.vedansh.unifiedmusic.catalog

data class Track(
    val id: String,
    val providerId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val source: TrackSource,
    val availability: TrackAvailability = TrackAvailability.UNKNOWN
)

enum class TrackSource(val label: String) {
    LOCAL("On device"), YOUTUBE("YouTube"), SPOTIFY("Spotify"), DEMO("Demo")
}

enum class TrackAvailability {
    AVAILABLE, UNAVAILABLE, REQUIRES_SUBSCRIPTION, UNKNOWN
}
