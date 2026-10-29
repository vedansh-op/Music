package com.vedansh.unifiedmusic.catalog

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.unifiedMusicDataStore by preferencesDataStore(name = "unified_music")

/** Persists liked local track IDs without storing or copying the audio files themselves. */
class FavoritesRepository(context: Context) {
    private val dataStore = context.applicationContext.unifiedMusicDataStore

    val favoriteTrackIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[FAVORITE_TRACK_IDS] ?: emptySet()
    }

    suspend fun setFavorite(trackId: String, favorite: Boolean) {
        if (trackId.isBlank()) return
        dataStore.edit { preferences ->
            val updated = (preferences[FAVORITE_TRACK_IDS] ?: emptySet()).toMutableSet()
            if (favorite) updated.add(trackId) else updated.remove(trackId)
            preferences[FAVORITE_TRACK_IDS] = updated
        }
    }

    private companion object {
        val FAVORITE_TRACK_IDS = stringSetPreferencesKey("favorite_track_ids")
    }
}
