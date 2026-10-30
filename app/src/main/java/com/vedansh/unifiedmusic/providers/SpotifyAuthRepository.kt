package com.vedansh.unifiedmusic.providers

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class SpotifyAuthorization(val uri: Uri, val verifier: String, val state: String)

data class StreamTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val provider: StreamProvider,
    val playbackId: String,
    val webUrl: String?
)

enum class StreamProvider { YOUTUBE, SPOTIFY }

/** Spotify Authorization Code + PKCE and Web API client. No client secret is embedded. */
class SpotifyAuthRepository(context: Context) {
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasStoredSession(): Boolean = preferences.getString(KEY_REFRESH_TOKEN, null) != null

    fun createAuthorization(clientId: String): SpotifyAuthorization {
        require(clientId.isNotBlank()) { "Spotify Client ID is not configured." }
        val verifier = randomUrlSafe(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val state = randomUrlSafe(32)
        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()
        preferences.edit()
            .putString(KEY_CODE_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .apply()
        return SpotifyAuthorization(uri, verifier, state)
    }

    fun expectedState(): String? = preferences.getString(KEY_STATE, null)
    fun pendingVerifier(): String? = preferences.getString(KEY_CODE_VERIFIER, null)

    suspend fun exchangeCode(code: String, verifier: String, clientId: String) =
        withContext(Dispatchers.IO) {
            val json = postToken(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to clientId,
                    "code_verifier" to verifier
                )
            )
            storeTokenResponse(json)
            preferences.edit().remove(KEY_CODE_VERIFIER).remove(KEY_STATE).apply()
        }

    suspend fun freshAccessToken(clientId: String): String? = withContext(Dispatchers.IO) {
        val access = preferences.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (!access.isNullOrBlank() && expiresAt > System.currentTimeMillis() + 60_000L) {
            return@withContext access
        }
        val refresh = preferences.getString(KEY_REFRESH_TOKEN, null) ?: return@withContext null
        val json = postToken(
            mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId)
        )
        storeTokenResponse(json)
        preferences.getString(KEY_ACCESS_TOKEN, null)
    }

    suspend fun searchTracks(query: String, accessToken: String): List<StreamTrack> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            val connection = URL("$API_BASE/search?type=track&limit=25&q=$encoded")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IOException("Spotify search failed (HTTP $status): $body")
                }
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val items = root.optJSONObject("tracks")?.optJSONArray("items") ?: return@withContext emptyList()
                buildList {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        val artist = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                        val album = item.optJSONObject("album")
                        val images = album?.optJSONArray("images")
                        val image = images?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                        val url = item.optJSONObject("external_urls")?.optString("spotify")
                        add(
                            StreamTrack(
                                id = "spotify:$id",
                                title = item.optString("name", "Unknown track"),
                                artist = artist.ifBlank { "Unknown artist" },
                                artworkUrl = image,
                                provider = StreamProvider.SPOTIFY,
                                playbackId = "spotify:track:$id",
                                webUrl = url
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

    fun clearSession() { preferences.edit().clear().apply() }

    private fun storeTokenResponse(json: JSONObject) {
        val accessToken = json.optString("access_token")
        if (accessToken.isBlank()) throw IOException("Spotify did not return an access token.")
        val editor = preferences.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L)
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { editor.putString(KEY_REFRESH_TOKEN, it) }
        editor.apply()
    }

    private fun postToken(parameters: Map<String, String>): JSONObject {
        val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = parameters.entries.joinToString("&") { (key, value) ->
            "${formEncode(key)}=${formEncode(value)}"
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Spotify authorization failed (HTTP $status): $response")
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun formEncode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val REDIRECT_URI = "unifiedmusic://spotify/callback"
        private const val PREFS_NAME = "provider_oauth_tokens"
        private const val KEY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_EXPIRES_AT = "spotify_expires_at"
        private const val KEY_CODE_VERIFIER = "spotify_code_verifier"
        private const val KEY_STATE = "spotify_oauth_state"
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val API_BASE = "https://api.spotify.com/v1"
        private const val SCOPES = "app-remote-control user-read-private user-read-email user-library-read playlist-read-private"
        private const val TIMEOUT_MS = 15_000
    }
}
