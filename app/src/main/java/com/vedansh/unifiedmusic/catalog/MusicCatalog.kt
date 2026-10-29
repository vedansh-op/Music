package com.vedansh.unifiedmusic.catalog

interface MusicCatalog {
    /** Returns only tracks with a usable online stream URL. */
    suspend fun search(query: String): List<Track>
}
