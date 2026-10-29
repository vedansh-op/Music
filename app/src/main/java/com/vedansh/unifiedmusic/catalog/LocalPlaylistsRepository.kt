package com.vedansh.unifiedmusic.catalog

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class LocalPlaylist(
    val id: String,
    val name: String,
    val trackIds: Set<String>
)

/** Persists user-created playlists; all media remains in Android's local library. */
class LocalPlaylistsRepository(context: Context) {
    private val dataStore = context.applicationContext.unifiedMusicDataStore

    val playlists: Flow<List<LocalPlaylist>> = dataStore.data.map { preferences ->
        (preferences[PLAYLIST_IDS] ?: emptySet())
            .mapNotNull { id ->
                val name = preferences[playlistNameKey(id)] ?: return@mapNotNull null
                LocalPlaylist(id, name, preferences[playlistTrackIdsKey(id)] ?: emptySet())
            }
            .sortedBy { it.name.lowercase() }
    }

    /** Returns the existing playlist ID if a case-insensitive duplicate name already exists. */
    suspend fun createPlaylist(name: String): String? {
        val cleanName = name.trim().take(60)
        if (cleanName.isBlank()) return null

        var savedId: String? = null
        dataStore.edit { preferences ->
            val ids = preferences[PLAYLIST_IDS] ?: emptySet()
            val existingId = ids.firstOrNull { id ->
                preferences[playlistNameKey(id)]?.equals(cleanName, ignoreCase = true) == true
            }
            if (existingId != null) {
                savedId = existingId
                return@edit
            }

            val id = UUID.randomUUID().toString()
            preferences[PLAYLIST_IDS] = ids + id
            preferences[playlistNameKey(id)] = cleanName
            preferences[playlistTrackIdsKey(id)] = emptySet()
            savedId = id
        }
        return savedId
    }

    suspend fun setTrackInPlaylist(playlistId: String, trackId: String, included: Boolean) {
        if (playlistId.isBlank() || trackId.isBlank()) return
        dataStore.edit { preferences ->
            val playlistIds = preferences[PLAYLIST_IDS] ?: emptySet()
            if (playlistId !in playlistIds) return@edit

            val key = playlistTrackIdsKey(playlistId)
            val updated = (preferences[key] ?: emptySet()).toMutableSet()
            if (included) updated.add(trackId) else updated.remove(trackId)
            preferences[key] = updated
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        if (playlistId.isBlank()) return
        dataStore.edit { preferences ->
            val ids = preferences[PLAYLIST_IDS] ?: emptySet()
            if (playlistId !in ids) return@edit
            preferences[PLAYLIST_IDS] = ids - playlistId
            preferences.remove(playlistNameKey(playlistId))
            preferences.remove(playlistTrackIdsKey(playlistId))
        }
    }

    private companion object {
        val PLAYLIST_IDS = stringSetPreferencesKey("local_playlist_ids")
        fun playlistNameKey(id: String) = stringPreferencesKey("local_playlist_name_$id")
        fun playlistTrackIdsKey(id: String) = stringSetPreferencesKey("local_playlist_tracks_$id")
    }
}
