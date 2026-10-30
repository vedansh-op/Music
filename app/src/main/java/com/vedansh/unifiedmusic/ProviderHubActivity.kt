package com.vedansh.unifiedmusic

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.vedansh.unifiedmusic.providers.SpotifyAuthRepository
import com.vedansh.unifiedmusic.providers.StreamProvider
import com.vedansh.unifiedmusic.providers.StreamTrack
import com.vedansh.unifiedmusic.providers.YouTubeCatalog
import com.vedansh.unifiedmusic.providers.YouTubePlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HubBackground = Color(0xFF101114)
private val HubPanel = Color(0xFF1B1D22)
private val HubAccent = Color(0xFFB7F36B)

class ProviderHubActivity : ComponentActivity() {
    private val spotifyRepository by lazy { SpotifyAuthRepository(applicationContext) }
    private val providerStatus by lazy { getSharedPreferences("provider_status", MODE_PRIVATE) }
    private var spotifyRemote: SpotifyAppRemote? = null
    private var pendingYoutubeAction: ((String) -> Unit)? = null
    private var pendingSpotifyTrack: String? = null

    private var youtubeConnected by mutableStateOf(false)
    private var spotifyConnected by mutableStateOf(false)
    private var spotifyClientId by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var results by mutableStateOf<List<StreamTrack>>(emptyList())
    private var youtubePlaylists by mutableStateOf<List<YouTubePlaylist>>(emptyList())
    private var youtubeLibraryBusy by mutableStateOf(false)
    private var youtubeLibraryStatus by mutableStateOf("Load playlists from the Google account connected to YouTube.")
    private var youtubeNextPageToken by mutableStateOf<String?>(null)
    private var youtubeSearchQuery by mutableStateOf("")
    private var youtubeLoadingMore by mutableStateOf(false)
    private var status by mutableStateOf("Connect your own accounts to start searching.")
    private var error by mutableStateOf<String?>(null)

    private val googleAuthorizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val returnedIntent = result.data
            if (returnedIntent == null) {
                youtubeConnected = false
                error = if (result.resultCode == Activity.RESULT_CANCELED) {
                    "Google sign-in was closed before it returned an authorization result. Check the OAuth test-user and consent-screen settings if this happened immediately after choosing an account."
                } else {
                    "Google sign-in returned no result (activity result code ${result.resultCode}). Try again and check the Google Cloud OAuth configuration."
                }
                busy = false
                pendingYoutubeAction = null
            } else {
                runCatching {
                    Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(returnedIntent)
                }.onSuccess { authorization ->
                    val token = authorization.accessToken
                    val granted = authorization.grantedScopes
                    if (token.isNullOrBlank()) {
                        youtubeConnected = false
                        error = "Google completed the account screen but returned no YouTube access token. Granted scopes: ${granted.joinToString().ifBlank { "none" }}."
                        busy = false
                    } else if (!granted.contains(YOUTUBE_READ_ONLY_SCOPE)) {
                        youtubeConnected = false
                        error = "Google did not grant the YouTube read-only permission. Granted scopes: ${granted.joinToString().ifBlank { "none" }}. Reconnect and approve the requested YouTube permission."
                        busy = false
                    } else {
                        val action = pendingYoutubeAction
                        pendingYoutubeAction = null
                        action?.invoke(token)
                    }
                }.onFailure { throwable ->
                    youtubeConnected = false
                    error = describeGoogleAuthorizationError(throwable)
                    busy = false
                    pendingYoutubeAction = null
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spotifyConnected = spotifyRepository.hasStoredSession()
        // Do not trust a persisted boolean as proof of an active Google authorization.
        // The account is marked connected only after a live token passes an API check.
        youtubeConnected = false
        spotifyClientId = providerStatus.getString("spotify_client_id", BuildConfig.SPOTIFY_CLIENT_ID).orEmpty()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = HubBackground,
                    surface = HubPanel,
                    primary = HubAccent,
                    onPrimary = HubBackground,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                ProviderHubScreen(
                    youtubeConnected = youtubeConnected,
                    spotifyConnected = spotifyConnected,
                    spotifyConfigured = spotifyClientId.isNotBlank(),
                    spotifyClientId = spotifyClientId,
                    youtubeConfigured = true,
                    busy = busy,
                    query = query,
                    results = results,
                    youtubePlaylists = youtubePlaylists,
                    youtubeLibraryBusy = youtubeLibraryBusy,
                    youtubeLibraryStatus = youtubeLibraryStatus,
                    youtubeHasMore = !youtubeNextPageToken.isNullOrBlank(),
                    youtubeLoadingMore = youtubeLoadingMore,
                    status = status,
                    error = error,
                    onQueryChange = { query = it },
                    onSpotifyClientIdChange = { spotifyClientId = it },
                    onSaveSpotifyClientId = { saveSpotifyClientId() },
                    onLoadYouTubePlaylists = { loadYouTubePlaylists() },
                    onYouTubePlaylistClick = { playlist -> openYouTubePlaylist(playlist) },
                    onLoadMoreYouTube = { loadMoreYouTubeResults() },
                    onConnectYouTube = { connectYouTube() },
                    onDisconnectYouTube = { disconnectYouTube() },
                    onConnectSpotify = { connectSpotify() },
                    onDisconnectSpotify = { disconnectSpotify() },
                    onSearch = { searchProviders() },
                    onTrackClick = { track -> playTrack(track) },
                    onOpenLocalLibrary = { startActivity(Intent(this, MainActivity::class.java)) }
                )
            }
        }
        handleSpotifyRedirect(intent?.data)
        restoreYouTubeConnectionSilently()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent.data)
    }

    override fun onDestroy() {
        spotifyRemote?.let { SpotifyAppRemote.disconnect(it) }
        spotifyRemote = null
        super.onDestroy()
    }

    /**
     * Restores an existing grant without launching consent UI on app startup.
     * If Google needs the user to choose an account or consent again, leave the app
     * disconnected until the user taps Connect Google.
     */
    private fun restoreYouTubeConnectionSilently() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(YOUTUBE_READ_ONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { authorization ->
                val token = authorization.accessToken
                if (!authorization.hasResolution() &&
                    !token.isNullOrBlank() &&
                    authorization.grantedScopes.contains(YOUTUBE_READ_ONLY_SCOPE)
                ) {
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                YouTubeCatalog.getAuthenticatedChannel(token, BuildConfig.YOUTUBE_API_KEY)
                            }
                        }.onSuccess { channel ->
                            youtubeConnected = true
                            status = if (channel.isNullOrBlank()) {
                                "YouTube authorization restored."
                            } else {
                                "Connected to YouTube as $channel."
                            }
                        }.onFailure { exception ->
                            youtubeConnected = false
                            status = exception.message ?: "Reconnect Google to restore YouTube access."
                        }
                    }
                } else {
                    youtubeConnected = false
                }
            }
            .addOnFailureListener { exception ->
                youtubeConnected = false
                status = describeGoogleAuthorizationError(exception)
            }
    }

    private fun connectYouTube() {
        error = null
        busy = true
        status = "Choose the Google account you want to connect…"
        requestYouTubeAuthorization(forceAccountSelection = true) { token ->
            lifecycleScope.launch {
                try {
                    val channel = withContext(Dispatchers.IO) {
                        YouTubeCatalog.getAuthenticatedChannel(token, BuildConfig.YOUTUBE_API_KEY)
                    }
                    youtubeConnected = true
                    status = if (channel.isNullOrBlank()) {
                        "Google authorization works. YouTube account access was verified."
                    } else {
                        "Connected to YouTube as $channel."
                    }
                    error = null
                } catch (exception: Exception) {
                    youtubeConnected = false
                    error = exception.message ?: "Google authorization succeeded, but YouTube account verification failed."
                    status = "Google account selected; YouTube API verification failed."
                } finally {
                    busy = false
                }
            }
        }
    }

    private fun requestYouTubeAuthorization(
        forceAccountSelection: Boolean = false,
        action: (String) -> Unit
    ) {
        pendingYoutubeAction = action
        status = if (forceAccountSelection) {
            "Opening Google account chooser…"
        } else {
            "Checking Google authorization for YouTube…"
        }
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(YOUTUBE_READ_ONLY_SCOPE)))
        if (forceAccountSelection) {
            builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        }
        Identity.getAuthorizationClient(this)
            .authorize(builder.build())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        youtubeConnected = false
                        error = "Google Play services requested an authorization screen but did not provide one. Update Google Play services and try again."
                        pendingYoutubeAction = null
                        busy = false
                    } else {
                        runCatching {
                            googleAuthorizationLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            )
                        }.onFailure { throwable ->
                            youtubeConnected = false
                            error = describeGoogleAuthorizationError(throwable)
                            pendingYoutubeAction = null
                            busy = false
                        }
                    }
                } else {
                    val token = result.accessToken
                    val granted = result.grantedScopes
                    if (token.isNullOrBlank() || !granted.contains(YOUTUBE_READ_ONLY_SCOPE)) {
                        youtubeConnected = false
                        error = "Google did not return a usable YouTube token. Granted scopes: ${granted.joinToString().ifBlank { "none" }}."
                        pendingYoutubeAction = null
                        busy = false
                    } else {
                        val pendingAction = pendingYoutubeAction
                        pendingYoutubeAction = null
                        pendingAction?.invoke(token)
                    }
                }
            }
            .addOnFailureListener { exception ->
                youtubeConnected = false
                error = describeGoogleAuthorizationError(exception)
                pendingYoutubeAction = null
                busy = false
            }
    }

    private fun describeGoogleAuthorizationError(throwable: Throwable): String {
        val apiException = generateSequence(throwable) { it.cause }
            .filterIsInstance<ApiException>()
            .firstOrNull()
        val code = apiException?.statusCode
        val explanation = when (code) {
            10 -> "Google reports DEVELOPER_ERROR. The installed APK package name or signing SHA-1 does not match the Android OAuth client. For this APK use package com.vedansh.unifiedmusic and verify its signing SHA-1 in the build details."
            7 -> "Google reports NETWORK_ERROR. Check the phone's connection, date/time, and Google Play services."
            8 -> "Google reports INTERNAL_ERROR. Update Google Play services and retry."
            13 -> "Google reports a general authorization error. Check the OAuth consent screen, YouTube Data API enablement, and test-user access."
            12500 -> "Google reports SIGN_IN_FAILED. Check the OAuth Android client package/SHA-1 and Google Cloud consent-screen configuration."
            else -> "Google authorization failed. If the app's publishing status is Testing, only accounts listed under OAuth consent-screen Test users can authorize it."
        }
        val detail = throwable.localizedMessage?.takeIf { it.isNotBlank() }
        val codeText = code?.let { " Google status code: $it." }.orEmpty()
        return listOfNotNull(explanation + codeText, detail?.let { "Details: $it" }).joinToString("\n")
    }

    private fun disconnectYouTube() {
        youtubeConnected = false
        busy = true
        val request = RevokeAccessRequest.builder()
            .setScopes(listOf(Scope(YOUTUBE_READ_ONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .revokeAccess(request)
            .addOnCompleteListener { task ->
                busy = false
                status = if (task.isSuccessful) {
                    "YouTube access disconnected."
                } else {
                    "YouTube was disconnected from this app. Google may still show an existing authorization grant."
                }
            }
    }

    private fun loadYouTubePlaylists() {
        youtubeLibraryBusy = true
        youtubeLibraryStatus = "Authorizing Google and loading your playlists…"
        requestYouTubeAuthorization(forceAccountSelection = false) { token ->
            lifecycleScope.launch {
                try {
                    val playlists = withContext(Dispatchers.IO) {
                        YouTubeCatalog.getMyPlaylists(token, BuildConfig.YOUTUBE_API_KEY)
                    }
                    youtubePlaylists = playlists
                    youtubeLibraryStatus = if (playlists.isEmpty()) {
                        "No public or account-owned playlists were returned for this YouTube account."
                    } else {
                        "Loaded ${playlists.size} playlists from your YouTube account."
                    }
                } catch (exception: Exception) {
                    youtubeLibraryStatus = exception.message ?: "Couldn't load YouTube playlists."
                } finally {
                    youtubeLibraryBusy = false
                }
            }
        }
    }

    private fun openYouTubePlaylist(playlist: YouTubePlaylist) {
        youtubeLibraryBusy = true
        youtubeLibraryStatus = "Loading ${playlist.title}…"
        requestYouTubeAuthorization(forceAccountSelection = false) { token ->
            lifecycleScope.launch {
                try {
                    val tracks = withContext(Dispatchers.IO) {
                        YouTubeCatalog.getPlaylistTracks(
                            playlistId = playlist.id,
                            accessToken = token,
                            apiKey = BuildConfig.YOUTUBE_API_KEY
                        )
                    }
                    results = tracks
                    status = "${playlist.title} · ${tracks.size} videos loaded."
                    youtubeLibraryStatus = "Opened ${playlist.title}."
                } catch (exception: Exception) {
                    youtubeLibraryStatus = exception.message ?: "Couldn't open this playlist."
                } finally {
                    youtubeLibraryBusy = false
                }
            }
        }
    }

    private fun saveSpotifyClientId() {
        val value = spotifyClientId.trim()
        if (value.length < 10 || value.any { it.isWhitespace() }) {
            error = "Enter a valid Spotify Client ID from your Spotify Developer Dashboard."
            return
        }
        spotifyClientId = value
        providerStatus.edit().putString("spotify_client_id", value).apply()
        error = null
        status = "Spotify app configuration saved on this device."
    }

    private fun connectSpotify() {
        val clientId = spotifyClientId.trim()
        if (clientId.isBlank()) {
            error = "Add your Spotify Client ID in the Spotify setup section first."
            return
        }
        providerStatus.edit().putString("spotify_client_id", clientId).apply()
        spotifyClientId = clientId
        runCatching { spotifyRepository.createAuthorization(clientId) }
            .onSuccess { authorization ->
                status = "Opening Spotify sign-in. Approve access for Unified Music."
                error = null
                startActivity(Intent(Intent.ACTION_VIEW, authorization.uri))
            }
            .onFailure { error = it.message ?: "Couldn't start Spotify sign-in." }
    }

    private fun handleSpotifyRedirect(uri: Uri?) {
        if (uri?.scheme != "unifiedmusic" || uri.host != "spotify" || uri.path != "/callback") return
        val returnedError = uri.getQueryParameter("error")
        if (!returnedError.isNullOrBlank()) {
            error = "Spotify authorization was not approved: $returnedError"
            status = "Spotify is not connected."
            return
        }
        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        if (code.isNullOrBlank() || returnedState.isNullOrBlank() ||
            returnedState != spotifyRepository.expectedState()) {
            error = "Spotify returned an invalid authorization response. Try connecting again."
            return
        }
        val verifier = spotifyRepository.pendingVerifier()
        if (verifier.isNullOrBlank()) {
            error = "The Spotify sign-in session expired. Try connecting again."
            return
        }
        busy = true
        lifecycleScope.launch {
            try {
                spotifyRepository.exchangeCode(code, verifier, spotifyClientId.trim())
                spotifyConnected = true
                status = "Spotify account authorized. Connecting to Spotify playback…"
                error = null
                connectSpotifyRemote()
            } catch (exception: Exception) {
                error = "Spotify sign-in failed: ${exception.message.orEmpty()}"
                spotifyConnected = false
            } finally {
                busy = false
            }
        }
    }

    private fun connectSpotifyRemote() {
        val clientId = spotifyClientId.trim()
        if (clientId.isBlank()) return
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(SpotifyAuthRepository.REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(
            this,
            params,
            object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    spotifyRemote?.let { SpotifyAppRemote.disconnect(it) }
                    spotifyRemote = appRemote
                    spotifyConnected = true
                    status = "Spotify connected. Playback is controlled through the Spotify app."
                    error = null
                    pendingSpotifyTrack?.let { uri ->
                        appRemote.playerApi.play(uri)
                        pendingSpotifyTrack = null
                    }
                }

                override fun onFailure(throwable: Throwable) {
                    spotifyRemote = null
                    spotifyConnected = spotifyRepository.hasStoredSession()
                    error = "Spotify account is authorized, but playback connection failed. Keep Spotify installed and logged in, then tap Connect Spotify again. ${throwable.message.orEmpty()}"
                }
            }
        )
    }

    private fun disconnectSpotify() {
        spotifyRemote?.let { SpotifyAppRemote.disconnect(it) }
        spotifyRemote = null
        spotifyRepository.clearSession()
        spotifyConnected = false
        status = "Spotify disconnected from Unified Music."
    }

    private fun searchProviders() {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            error = "Enter a song or artist first."
            return
        }
        results = emptyList()
        youtubeNextPageToken = null
        youtubeSearchQuery = cleanQuery
        error = null
        busy = true
        status = "Searching YouTube and Spotify…"

        requestYouTubeAuthorization(forceAccountSelection = false) { token ->
            lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) {
                        YouTubeCatalog.searchPage(cleanQuery, token, BuildConfig.YOUTUBE_API_KEY)
                    }
                    youtubeNextPageToken = page.nextPageToken
                    results = (results + page.tracks).distinctBy { it.id }
                } catch (exception: Exception) {
                    error = listOfNotNull(error, exception.message).joinToString("\n")
                } finally {
                    busy = false
                    status = "Search results from connected providers."
                }
            }
        }

        lifecycleScope.launch {
            try {
                val token = spotifyRepository.freshAccessToken(spotifyClientId.trim())
                if (token == null) {
                    error = listOfNotNull(error, "Connect Spotify to search its catalogue.").joinToString("\n")
                } else {
                    val tracks = spotifyRepository.searchTracks(cleanQuery, token)
                    results = (results + tracks).distinctBy { it.id }
                }
            } catch (exception: Exception) {
                error = listOfNotNull(error, exception.message).joinToString("\n")
            } finally {
                status = "Search results from connected providers."
            }
        }
    }

    private fun loadMoreYouTubeResults() {
        val pageToken = youtubeNextPageToken ?: return
        val searchTerm = youtubeSearchQuery.ifBlank { query.trim() }
        if (searchTerm.isBlank()) return
        youtubeLoadingMore = true
        requestYouTubeAuthorization(forceAccountSelection = false) { accessToken ->
            lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) {
                        YouTubeCatalog.searchPage(
                            query = searchTerm,
                            accessToken = accessToken,
                            apiKey = BuildConfig.YOUTUBE_API_KEY,
                            pageToken = pageToken
                        )
                    }
                    results = (results + page.tracks).distinctBy { it.id }
                    youtubeNextPageToken = page.nextPageToken
                    status = "Loaded more YouTube results."
                    error = null
                } catch (exception: Exception) {
                    error = exception.message ?: "Couldn't load more YouTube results."
                } finally {
                    youtubeLoadingMore = false
                }
            }
        }
    }

    private fun playTrack(track: StreamTrack) {
        error = null
        when (track.provider) {
            StreamProvider.YOUTUBE -> startActivity(
                Intent(this, YouTubePlayerActivity::class.java)
                    .putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, track.playbackId)
                    .putExtra(YouTubePlayerActivity.EXTRA_TITLE, track.title)
            )
            StreamProvider.SPOTIFY -> {
                val remote = spotifyRemote
                if (remote != null) {
                    remote.playerApi.play(track.playbackId)
                    status = "Playing through Spotify."
                } else {
                    pendingSpotifyTrack = track.playbackId
                    connectSpotifyRemote()
                }
            }
        }
    }

    companion object {
        private const val YOUTUBE_READ_ONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    }
}

@Composable
private fun ProviderHubScreen(
    youtubeConnected: Boolean,
    spotifyConnected: Boolean,
    spotifyConfigured: Boolean,
    spotifyClientId: String,
    youtubeConfigured: Boolean,
    busy: Boolean,
    query: String,
    results: List<StreamTrack>,
    youtubePlaylists: List<YouTubePlaylist>,
    youtubeLibraryBusy: Boolean,
    youtubeLibraryStatus: String,
    youtubeHasMore: Boolean,
    youtubeLoadingMore: Boolean,
    status: String,
    error: String?,
    onQueryChange: (String) -> Unit,
    onSpotifyClientIdChange: (String) -> Unit,
    onSaveSpotifyClientId: () -> Unit,
    onLoadYouTubePlaylists: () -> Unit,
    onYouTubePlaylistClick: (YouTubePlaylist) -> Unit,
    onLoadMoreYouTube: () -> Unit,
    onConnectYouTube: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onSearch: () -> Unit,
    onTrackClick: (StreamTrack) -> Unit,
    onOpenLocalLibrary: () -> Unit
) {
    Scaffold(containerColor = HubBackground) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(20.dp))
                Text("UNIFIED MUSIC", color = HubAccent, style = MaterialTheme.typography.labelMedium)
                Text("Your music, together.", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text("Connect your own accounts. Search both catalogues from one place.", color = Color.LightGray)
                Spacer(Modifier.height(6.dp))
            }
            item {
                ProviderCard(
                    title = "YouTube",
                    subtitle = "Google account · YouTube catalogue and visible YouTube player",
                    connected = youtubeConnected,
                    configured = youtubeConfigured,
                    actionLabel = if (youtubeConnected) "Disconnect" else "Connect Google",
                    onAction = if (youtubeConnected) onDisconnectYouTube else onConnectYouTube
                )
            }
            item {
                ProviderCard(
                    title = "Spotify",
                    subtitle = "Spotify account · Search and play through the Spotify app",
                    connected = spotifyConnected,
                    configured = spotifyConfigured,
                    actionLabel = if (spotifyConnected) "Disconnect" else "Connect Spotify",
                    onAction = if (spotifyConnected) onDisconnectSpotify else onConnectSpotify
                )
            }
            item {
                SpotifySetupCard(
                    clientId = spotifyClientId,
                    onClientIdChange = onSpotifyClientIdChange,
                    onSave = onSaveSpotifyClientId,
                    configured = spotifyConfigured
                )
            }
            item {
                Surface(color = HubPanel, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(status, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        if (!error.isNullOrBlank()) {
                            Text(error, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                YouTubeLibraryHeader(
                    loading = youtubeLibraryBusy,
                    status = youtubeLibraryStatus,
                    onLoad = onLoadYouTubePlaylists
                )
            }
            items(youtubePlaylists, key = { it.id }) { playlist ->
                YouTubePlaylistRow(playlist, onClick = { onYouTubePlaylistClick(playlist) })
            }
            item {
                Text("SEARCH MUSIC", color = HubAccent, style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Song, artist or album") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Button(
                    onClick = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = HubAccent, contentColor = HubBackground)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HubBackground)
                    else Icon(Icons.Default.Link, contentDescription = null)
                    Text("  Search YouTube + Spotify")
                }
            }
            if (results.isEmpty() && !busy) {
                item {
                    Surface(color = HubPanel, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("No results yet", fontWeight = FontWeight.SemiBold)
                            Text("Connect your accounts, search for a track, then play a result.", color = Color.LightGray)
                        }
                    }
                }
            } else {
                items(results.size) { index ->
                    StreamTrackRow(results[index], onClick = { onTrackClick(results[index]) })
                }
            }
            if (youtubeHasMore) {
                item {
                    Button(
                        onClick = onLoadMoreYouTube,
                        enabled = !youtubeLoadingMore,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = HubPanel, contentColor = HubAccent)
                    ) {
                        if (youtubeLoadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HubAccent)
                        else Icon(Icons.Default.Link, contentDescription = null)
                        Text("  Load more YouTube results")
                    }
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onOpenLocalLibrary, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null)
                    Text("  Open existing local music library")
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "YouTube uses its visible embedded player. Spotify playback is handled by the Spotify app. Provider audio is not extracted into the local Media3 player.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun YouTubeLibraryHeader(
    loading: Boolean,
    status: String,
    onLoad: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = HubPanel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YOUR YOUTUBE LIBRARY", color = HubAccent, style = MaterialTheme.typography.labelLarge)
            Text("Browse playlists from the Google account you connect. This uses the official YouTube Data API.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onLoad,
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = HubAccent, contentColor = HubBackground)
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HubBackground)
                else Icon(Icons.Default.MusicNote, contentDescription = null)
                Text("  Load my YouTube playlists")
            }
            Text(status, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun YouTubePlaylistRow(
    playlist: YouTubePlaylist,
    onClick: () -> Unit
) {
    Surface(
        color = HubPanel,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!playlist.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = HubAccent, modifier = Modifier.size(52.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(playlist.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.itemCount} videos", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.PlayArrow, contentDescription = "Open playlist", tint = HubAccent)
        }
    }
}

@Composable
private fun SpotifySetupCard(
    clientId: String,
    onClientIdChange: (String) -> Unit,
    onSave: () -> Unit,
    configured: Boolean
) {
    Card(colors = CardDefaults.cardColors(containerColor = HubPanel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SPOTIFY APP SETUP", color = HubAccent, style = MaterialTheme.typography.labelLarge)
            Text(
                if (configured) "Client ID saved on this device. You can update it below."
                else "One-time setup: create an app in the Spotify Developer Dashboard and paste its Client ID here.",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = onClientIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Spotify Client ID") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Text(
                "Register this exact redirect URI in your Spotify app: unifiedmusic://spotify/callback. Never enter a Spotify Client Secret here.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = onSave,
                enabled = clientId.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = HubAccent, contentColor = HubBackground)
            ) {
                Text("Save Spotify setup")
            }
        }
    }
}

@Composable
private fun ProviderCard(
    title: String,
    subtitle: String,
    connected: Boolean,
    configured: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = HubPanel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = HubAccent, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (connected) "Connected" else "Not connected", color = if (connected) HubAccent else Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = onAction,
                    enabled = connected || configured || title == "YouTube",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connected) Color(0xFF3A3D44) else HubAccent,
                        contentColor = if (connected) Color.White else HubBackground
                    )
                ) { Text(actionLabel) }
            }
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            if (!configured && !connected) {
                Text(
                    if (title == "Spotify") "Needs Spotify Developer Client ID configuration." else "Enable YouTube Data API v3 in the Google Cloud project linked to your Android OAuth client.",
                    color = Color(0xFFFFD18B),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun StreamTrackRow(track: StreamTrack, onClick: () -> Unit) {
    Surface(
        color = HubPanel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = HubAccent, modifier = Modifier.size(54.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.provider.name, color = HubAccent, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = HubAccent)
        }
    }
}
