package com.vedansh.unifiedmusic.catalog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads music already stored on the device. This repository never uploads or copies audio.
 * The caller is responsible for requesting the appropriate runtime permission first.
 */
class LocalMusicRepository(private val context: Context) {

    fun hasReadPermission(): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * MediaStore can contain thousands of rows, so execute the query off the main thread.
     * A missing permission or a device with no audio files is represented by an empty list.
     */
    suspend fun getTracks(): List<Track> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()

        val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sortOrder = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        val tracks = mutableListOf<Track>()

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = Uri.withAppendedPath(collection, id.toString())
                val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: "Unknown title"
                val artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                val album = cursor.getString(albumColumn)?.takeIf { it.isNotBlank() }
                val albumId = cursor.getLong(albumIdColumn).takeIf { it > 0L }
                val artworkUri = albumId?.let { "content://media/external/audio/albumart/" + it }
                val duration = cursor.getLong(durationColumn).takeIf { it > 0L }

                tracks += Track(
                    id = "local:" + id,
                    providerId = contentUri.toString(),
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUrl = artworkUri,
                    durationMs = duration,
                    source = TrackSource.LOCAL,
                    availability = TrackAvailability.AVAILABLE
                )
            }
        }
        tracks
    }
}
