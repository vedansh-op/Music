package com.vedansh.unifiedmusic

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import androidx.compose.material3.OutlinedButton
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
import com.vedansh.unifiedmusic.providers.GuestSuggestion
import com.vedansh.unifiedmusic.providers.GuestTasteRepository
import com.vedansh.unifiedmusic.providers.GuestYouTubeRepository
import com.vedansh.unifiedmusic.providers.SpotifyAuthRepository
import com.vedansh.unifiedmusic.providers.StreamProvider
import com.vedansh.unifiedmusic.providers.StreamTrack
import com.vedansh.unifiedmusic.providers.YouTubeCatalog
import com.vedansh.unifiedmusic.providers.YouTubePlaylist
import com.vedansh.unifiedmusic.playback.MusicPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HubBackground = Color(0xFF101114)
private val HubPanel = Color(0xFF1B1D22)
private val HubAccent = Color(0xFFB7F36B)

class ProviderHubActivity : ComponentActivity() {
    private val spotifyRepository by lazy { SpotifyAuthRepository(applicationContext) }
    private val providerStatus by lazy { getSharedPreferences("provider_status", MODE_PRIVATE) }
    private val guestTaste by lazy { GuestTasteRepository(applicationContext) }
    private var guestMode by mutableStateOf(false)
    private var showWelcome by mutableStateOf(false)
    private var guestSuggestions by mutableStateOf<List<GuestSuggestion>>(emptyList())
    private var spotifyRemote: SpotifyAppRemote? = null
    private var guestMediaController: MediaController? = null
    private var guestMediaControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var pendingYoutubeAction: ((String) -> Unit)? = null
    private var pendingYoutubeFailure: (() -> Unit)? = null
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
                val message = if (result.resultCode == Activity.RESULT_CANCELED) {
                    "Google account chooser was cancelled by the user."
                } else {
                    "Google sign-in returned no result (activity result code ${result.resultCode})."
                }
                failPendingYouTubeAuthorization(message, wasCancelled = result.resultCode == Activity.RESULT_CANCELED)
            } else {
                runCatching {
                    Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(returnedIntent)
                }.onSuccess { authorization ->
                    val token = authorization.accessToken
                    val granted = authorization.grantedScopes
                    if (token.isNullOrBlank()) {
                        failPendingYouTubeAuthorization(
                            "Google completed the account screen but returned no YouTube access token. Granted scopes: ${granted.joinToString().ifBlank { "none" }}."
                        )
                    } else if (!granted.contains(YOUTUBE_READ_ONLY_SCOPE)) {
                        failPendingYouTubeAuthorization(
                            "Google did not grant the YouTube read-only permission. Granted scopes: ${granted.joinToString().ifBlank { "none" }}. Reconnect and approve the requested YouTube permission."
                        )
                    } else {
                        val action = pendingYoutubeAction
                        pendingYoutubeAction = null
                        pendingYoutubeFailure = null
                        action?.invoke(token)
                    }
                }.onFailure { throwable ->
                    failPendingYouTubeAuthorization(describeGoogleAuthorizationError(throwable))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spotifyConnected = spotifyRepository.hasStoredSession()
        guestMode = providerStatus.getBoolean(KEY_GUEST_MODE, false)
        showWelcome = !providerStatus.getBoolean(KEY_WELCOME_COMPLETED, false) && !spotifyConnected
        guestSuggestions = guestTaste.suggestions()
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
                    guestMode = guestMode,
                    showWelcome = showWelcome,
                    guestSuggestions = guestSuggestions,
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
                    onLoadYouTubeLikedVideos = { loadYouTubeLikedVideos() },
                    onYouTubePlaylistClick = { playlist -> openYouTubePlaylist(playlist) },
                    onLoadMoreYouTube = { loadMoreYouTubeResults() },
                    onConnectYouTube = { connectYouTube() },
                    onDisconnectYouTube = { disconnectYouTube() },
                    onConnectSpotify = { connectSpotify() },
                    onDisconnectSpotify = { disconnectSpotify() },
                    onSearch = { searchProviders() },
                    onTrackClick = { track -> playTrack(track) },
                    onContinueAsGuest = { continueAsGuest() },
                    onGuestSuggestionClick = { suggestion -> searchGuestSuggestion(suggestion) },
                    onOpenLocalLibrary = { startActivity(Intent(this, MainActivity::class.java)) }
                )
            }
        }
        handleSpotifyRedirect(intent?.data)
        if (!guestMode) restoreYouTubeConnectionSilently()
    }

    override fun onResume() {
        super.onResume()
        guestSuggestions = guestTaste.suggestions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent.data)
    }

    override fun onDestroy() {
        spotifyRemote?.let { SpotifyAppRemote.disconnect(it) }
        spotifyRemote = null
        guestMediaController?.release()
        guestMediaController = null
        guestMediaControllerFuture?.let(MediaController::releaseFuture)
        guestMediaControllerFuture = null
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
                            guestMode = false
                            showWelcome = false
                            providerStatus.edit()
                                .putBoolean(KEY_GUEST_MODE, false)
                                .putBoolean(KEY_WELCOME_COMPLETED, true)
                                .apply()
                            status = if (channel.isNullOrBlank()) {
                                "YouTube authorization restored."
                            } else {
                                "Connected to YouTube as $channel."
                            }
                        }.onFailure { exception ->
                            youtubeConnected = false
                            logYouTubeFailure("silent reconnect verification", exception)
                            status = "Couldn't reconnect YouTube. Tap Connect Google to try again."
                        }
                    }
                } else {
                    youtubeConnected = false
                }
            }
            .addOnFailureListener { exception ->
                youtubeConnected = false
                logYouTubeFailure("silent authorization restore", exception)
                status = "Tap Connect Google to connect your YouTube account."
            }
    }

    private fun connectYouTube() {
        prepareAccountConnection()
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
                    guestMode = false
                    showWelcome = false
                    providerStatus.edit()
                        .putBoolean(KEY_GUEST_MODE, false)
                        .putBoolean(KEY_WELCOME_COMPLETED, true)
                        .apply()
                    status = if (channel.isNullOrBlank()) {
                        "Google authorization works. YouTube account access was verified."
                    } else {
                        "Connected to YouTube as $channel."
                    }
                    error = null
                } catch (exception: Exception) {
                    youtubeConnected = false
                    logYouTubeFailure("account verification", exception)
                    error = "Couldn't finish connecting YouTube. Please try again."
                    status = "Your YouTube account wasn't connected."
                } finally {
                    busy = false
                }
            }
        }
    }

    private fun requestYouTubeAuthorization(
        forceAccountSelection: Boolean = false,
        onFailure: (() -> Unit)? = null,
        action: (String) -> Unit
    ) {
        pendingYoutubeAction = action
        pendingYoutubeFailure = onFailure
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
                        failPendingYouTubeAuthorization(
                            "Google Play services requested an authorization screen but did not provide one. Update Google Play services and try again."
                        )
                    } else {
                        runCatching {
                            googleAuthorizationLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            )
                        }.onFailure { throwable ->
                            failPendingYouTubeAuthorization(describeGoogleAuthorizationError(throwable))
                        }
                    }
                } else {
                    val token = result.accessToken
                    val granted = result.grantedScopes
                    if (token.isNullOrBlank() || !granted.contains(YOUTUBE_READ_ONLY_SCOPE)) {
                        failPendingYouTubeAuthorization(
                            "Google did not return a usable YouTube token. Granted scopes: ${granted.joinToString().ifBlank { "none" }}."
                        )
                    } else {
                        val pendingAction = pendingYoutubeAction
                        pendingYoutubeAction = null
                        pendingYoutubeFailure = null
                        pendingAction?.invoke(token)
                    }
                }
            }
            .addOnFailureListener { exception ->
                failPendingYouTubeAuthorization(describeGoogleAuthorizationError(exception))
            }
    }

    private fun failPendingYouTubeAuthorization(message: String, wasCancelled: Boolean = false) {
        Log.w(LOG_TAG, "YouTube authorization did not complete: $message")
        youtubeConnected = false
        error = if (wasCancelled) null else "Couldn't connect Google right now. Please try again."
        status = if (wasCancelled) "Connection cancelled. You can connect Google any time." else "Your YouTube account wasn't connected."
        busy = false
        pendingYoutubeAction = null
        val failure = pendingYoutubeFailure
        pendingYoutubeFailure = null
        failure?.invoke()
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

    private fun logYouTubeFailure(operation: String, throwable: Throwable) {
        Log.e(LOG_TAG, "YouTube $operation failed", throwable)
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
        requestYouTubeAuthorization(
            forceAccountSelection = false,
            onFailure = {
                youtubeLibraryBusy = false
                youtubeLibraryStatus = error ?: "Google authorization failed. Try loading playlists again."
            }
        ) { token ->
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
                    logYouTubeFailure("load playlists", exception)
                    youtubeLibraryStatus = "Couldn't load your YouTube playlists. Please try again."
                } finally {
                    youtubeLibraryBusy = false
                }
            }
        }
    }

    private fun loadYouTubeLikedVideos() {
        youtubeLibraryBusy = true
        youtubeLibraryStatus = "Loading videos liked by your Google account…"
        requestYouTubeAuthorization(
            forceAccountSelection = false,
            onFailure = {
                youtubeLibraryBusy = false
                youtubeLibraryStatus = error ?: "Google authorization failed. Try loading liked videos again."
            }
        ) { token ->
            lifecycleScope.launch {
                try {
                    val tracks = withContext(Dispatchers.IO) {
                        YouTubeCatalog.getLikedVideos(token, BuildConfig.YOUTUBE_API_KEY)
                    }
                    results = tracks
                    youtubeNextPageToken = null
                    status = "Your liked YouTube videos · ${tracks.size} loaded."
                    youtubeLibraryStatus = "Loaded your liked YouTube videos."
                } catch (exception: Exception) {
                    logYouTubeFailure("load liked videos", exception)
                    youtubeLibraryStatus = "Couldn't load your liked videos. Please try again."
                } finally {
                    youtubeLibraryBusy = false
                }
            }
        }
    }

    private fun openYouTubePlaylist(playlist: YouTubePlaylist) {
        youtubeNextPageToken = null
        youtubeSearchQuery = ""
        youtubeLibraryBusy = true
        youtubeLibraryStatus = "Loading ${playlist.title}…"
        requestYouTubeAuthorization(
            forceAccountSelection = false,
            onFailure = {
                youtubeLibraryBusy = false
                youtubeLibraryStatus = error ?: "Google authorization failed. Try opening the playlist again."
            }
        ) { token ->
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
                    logYouTubeFailure("open playlist", exception)
                    youtubeLibraryStatus = "Couldn't open this playlist. Please try again."
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
        prepareAccountConnection()
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
                guestMode = false
                showWelcome = false
                providerStatus.edit()
                    .putBoolean(KEY_GUEST_MODE, false)
                    .putBoolean(KEY_WELCOME_COMPLETED, true)
                    .apply()
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

    private fun searchGuestSuggestion(suggestion: GuestSuggestion) {
        query = suggestion.query
        searchProviders()
    }

    private fun openGuestYouTubeSearch(searchTerm: String) {
        val uri = Uri.parse("https://www.youtube.com/results")
            .buildUpon()
            .appendQueryParameter("search_query", searchTerm)
            .build()
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun continueAsGuest() {
        guestMode = true
        showWelcome = false
        providerStatus.edit()
            .putBoolean(KEY_GUEST_MODE, true)
            .putBoolean(KEY_WELCOME_COMPLETED, true)
            .apply()
        guestSuggestions = guestTaste.suggestions()
        error = null
        status = "You're in guest mode. No account or sign-in is required."
    }

    private fun prepareAccountConnection() {
        guestMode = false
        showWelcome = false
        providerStatus.edit()
            .putBoolean(KEY_GUEST_MODE, false)
            .putBoolean(KEY_WELCOME_COMPLETED, true)
            .apply()
    }

    private fun searchProviders() {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            error = "Enter a song or artist first."
            return
        }
        guestTaste.recordSearch(cleanQuery)
        guestSuggestions = guestTaste.suggestions()
        results = emptyList()
        youtubeNextPageToken = null
        youtubeSearchQuery = cleanQuery
        error = null
        busy = true
        status = "Searching YouTube Music…"

        // Public YouTube Music discovery is the guest experience. Google login is
        // deliberately not part of search or playback; it is only used for the
        // signed-in user's private library and likes.
        lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    GuestYouTubeRepository.searchPage(cleanQuery)
                }
                results = page.tracks
                youtubeNextPageToken = page.nextPageToken
                status = "YouTube Music guest search complete."
                error = if (page.tracks.isEmpty()) {
                    "No YouTube Music tracks found. Try a song title or artist."
                } else null
            } catch (exception: Exception) {
                Log.w(LOG_TAG, "YouTube Music guest search failed", exception)
                error = "YouTube Music search couldn't load right now. Check your connection and try again."
                status = "Guest search is still available; no Google sign-in is required."
            } finally {
                busy = false
            }
        }

        // Spotify remains separate and optional. It is searched only after the
        // user has already connected it; guest search never opens Spotify login.
        if (spotifyConnected) {
            lifecycleScope.launch {
                try {
                    val token = spotifyRepository.freshAccessToken(spotifyClientId.trim())
                    if (token != null) {
                        val tracks = spotifyRepository.searchTracks(cleanQuery, token)
                        results = (results + tracks).distinctBy { it.id }
                    }
                } catch (exception: Exception) {
                    Log.w(LOG_TAG, "Spotify search failed", exception)
                    if (results.isEmpty()) error = "Spotify search is temporarily unavailable."
                }
            }
        }
    }

    private fun loadMoreYouTubeResults() {
        val continuation = youtubeNextPageToken ?: return
        val searchTerm = youtubeSearchQuery.ifBlank { query.trim() }
        if (searchTerm.isBlank()) return
        youtubeLoadingMore = true
        lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    GuestYouTubeRepository.searchPage(searchTerm, continuation)
                }
                results = (results + page.tracks).distinctBy { it.id }
                youtubeNextPageToken = page.nextPageToken
                status = "Loaded more YouTube Music results."
                error = null
            } catch (exception: Exception) {
                Log.w(LOG_TAG, "YouTube Music pagination failed", exception)
                error = "Couldn't load more YouTube Music results. Please try again."
            } finally {
                youtubeLoadingMore = false
            }
        }
    }

    private fun playTrack(track: StreamTrack) {
        guestTaste.recordPlay(track)
        guestSuggestions = guestTaste.suggestions()
        error = null
        when (track.provider) {
            StreamProvider.YOUTUBE -> playYouTubeMusicTrack(track)
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

    private fun playYouTubeMusicTrack(track: StreamTrack) {
        fun start(controller: MediaController) {
            val item = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri("https://music.youtube.com/watch?v=${track.playbackId}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(track.artworkUrl?.let(Uri::parse))
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
            status = "Playing ${track.title} from YouTube Music."
        }

        guestMediaController?.let {
            start(it)
            return
        }

        val future = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        ).buildAsync()
        guestMediaControllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { controller ->
                        guestMediaController = controller
                        start(controller)
                    }
                    .onFailure { throwable ->
                        Log.e(LOG_TAG, "Could not connect to YouTube Music player", throwable)
                        error = "Couldn't start YouTube Music playback. Please try another track."
                    }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    companion object {
        private const val LOG_TAG = "UnifiedMusic"
        private const val KEY_GUEST_MODE = "guest_mode"
        private const val KEY_WELCOME_COMPLETED = "welcome_completed"
        private const val YOUTUBE_READ_ONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    }
}

@Composable
private fun ProviderHubScreen(
    guestMode: Boolean,
    showWelcome: Boolean,
    guestSuggestions: List<GuestSuggestion>,
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
    onLoadYouTubeLikedVideos: () -> Unit,
    onYouTubePlaylistClick: (YouTubePlaylist) -> Unit,
    onLoadMoreYouTube: () -> Unit,
    onConnectYouTube: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onSearch: () -> Unit,
    onTrackClick: (StreamTrack) -> Unit,
    onContinueAsGuest: () -> Unit,
    onGuestSuggestionClick: (GuestSuggestion) -> Unit,
    onOpenLocalLibrary: () -> Unit
) {
    if (showWelcome) {
        GuestWelcomeScreen(
            spotifyConfigured = spotifyConfigured,
            onContinueAsGuest = onContinueAsGuest,
            onConnectGoogle = onConnectYouTube,
            onConnectSpotify = onConnectSpotify
        )
        return
    }

    Scaffold(containerColor = HubBackground) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(20.dp))
                Text("UNIFIED MUSIC", color = HubAccent, style = MaterialTheme.typography.labelMedium)
                Text("Your music, together.", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text(
                    if (guestMode) "Listen without signing in. Connect accounts only when you need their personal libraries."
                    else "Connect accounts when you want their personal libraries. Guest listening is always available.",
                    color = Color.LightGray
                )
                Spacer(Modifier.height(6.dp))
            }
            if (guestMode) {
                item { GuestModeBanner() }
            } else if (!youtubeConnected) {
                item {
                    GuestFallbackCard(onContinueAsGuest = onContinueAsGuest)
                }
            }
            if (guestMode) {
                item {
                    GuestSuggestionsCard(
                        suggestions = guestSuggestions,
                        onSuggestionClick = onGuestSuggestionClick
                    )
                }
            }
            item {
                ProviderCard(
                    title = "YouTube",
                    subtitle = "Optional Google account · Personal YouTube Music library and liked songs. Guest search/playback works without it.",
                    connected = youtubeConnected,
                    configured = youtubeConfigured,
                    busy = busy,
                    actionLabel = if (youtubeConnected) "Disconnect" else "Connect Google",
                    onAction = if (youtubeConnected) onDisconnectYouTube else onConnectYouTube
                )
            }
            item {
                ProviderCard(
                    title = "Spotify",
                    subtitle = "Optional Spotify account · Search and playback through the Spotify app",
                    connected = spotifyConnected,
                    configured = spotifyConfigured,
                    busy = busy,
                    actionLabel = if (spotifyConnected) "Disconnect" else "Connect Spotify",
                    onAction = if (spotifyConnected) onDisconnectSpotify else onConnectSpotify
                )
            }
            // Developer-only fallback for local debug builds. End users should never
            // be asked to create provider apps or paste API credentials into the player.
            if (BuildConfig.DEBUG && !spotifyConfigured) {
                item {
                    SpotifySetupCard(
                        clientId = spotifyClientId,
                        onClientIdChange = onSpotifyClientIdChange,
                        onSave = onSaveSpotifyClientId,
                        configured = spotifyConfigured
                    )
                }
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
            if (!guestMode) {
                item {
                    YouTubeLibraryHeader(
                        loading = youtubeLibraryBusy,
                        status = youtubeLibraryStatus,
                        onLoadPlaylists = onLoadYouTubePlaylists,
                        onLoadLiked = onLoadYouTubeLikedVideos
                    )
                }
                items(youtubePlaylists, key = { it.id }) { playlist ->
                    YouTubePlaylistRow(playlist, onClick = { onYouTubePlaylistClick(playlist) })
                }
            }
            /* playlist rows are intentionally hidden in guest mode; private account libraries need sign-in. */
            item {
                Text("YOUTUBE MUSIC", color = HubAccent, style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search songs, artists, albums or playlists") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Button(
                    onClick = onSearch,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = HubAccent, contentColor = HubBackground)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HubBackground)
                    else Icon(Icons.Default.Link, contentDescription = null)
                    Text(
                        when {
                            guestMode -> "  Search YouTube Music"
                            spotifyConnected -> "  Search YouTube Music + Spotify"
                            else -> "  Search YouTube Music"
                        }
                    )
                }
            }
            if (results.isEmpty() && !busy) {
                item {
                    Surface(color = HubPanel, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("No results yet", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (guestMode) "Search the YouTube Music catalogue without signing in, or choose one of your personalised mixes above."
                                else "Search YouTube Music now. Connect Google only when you want your personal library.",
                                color = Color.LightGray
                            )
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
                    if (guestMode) "YouTube Music guest playback runs in the app's Media3 player. Your listening recommendations stay on this device."
                    else "YouTube Music guest playback runs in the app's Media3 player. Google sign-in is optional for personal library features; Spotify playback uses the official Spotify app.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GuestWelcomeScreen(
    spotifyConfigured: Boolean,
    onContinueAsGuest: () -> Unit,
    onConnectGoogle: () -> Unit,
    onConnectSpotify: () -> Unit
) {
    Scaffold(containerColor = HubBackground) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("UNIFIED MUSIC", color = HubAccent, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(14.dp))
            Text(
                "Your music. Your choice.",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Start listening right away. You don't need a Google or Spotify account to enter guest mode.",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = HubPanel),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("CONTINUE AS GUEST", color = HubAccent, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "No sign-up. No account chooser. Your recommendations learn from your searches and plays and stay on this device.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onContinueAsGuest,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = HubAccent, contentColor = HubBackground)
                    ) {
                        Text("Continue as Guest", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("OR CONNECT AN ACCOUNT", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onConnectGoogle, modifier = Modifier.fillMaxWidth()) {
                Text("Connect Google / YouTube", color = Color.White)
            }
            OutlinedButton(
                onClick = onConnectSpotify,
                enabled = spotifyConfigured,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (spotifyConfigured) "Connect Spotify" else "Spotify can be connected later", color = Color.White)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "You can connect or disconnect providers later. Guest mode is not a limited trial.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun GuestModeBanner() {
    Surface(color = HubPanel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("GUEST MODE", color = HubAccent, style = MaterialTheme.typography.labelLarge)
            Text("You're listening without an account. Your taste profile is saved locally on this phone.", color = Color.White)
            Text("Sign in is optional. Public YouTube search can work without connecting Google.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GuestFallbackCard(onContinueAsGuest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = HubPanel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SIGN-IN NOT WORKING?", color = HubAccent, style = MaterialTheme.typography.labelLarge)
            Text("Don't get stuck at the login screen. You can still enter as a guest and connect accounts later.", color = Color.LightGray)
            Button(
                onClick = onContinueAsGuest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = HubAccent, contentColor = HubBackground)
            ) {
                Text("Continue as Guest")
            }
        }
    }
}

@Composable
private fun GuestSuggestionsCard(
    suggestions: List<GuestSuggestion>,
    onSuggestionClick: (GuestSuggestion) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = HubPanel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("YOUR PERSONAL MIX", color = HubAccent, style = MaterialTheme.typography.labelLarge)
            Text(
                "These suggestions adapt to what you search and play. The listening profile stays on this device.",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall
            )
            suggestions.forEach { suggestion ->
                Surface(
                    color = Color(0xFF292C32),
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onSuggestionClick(suggestion) }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = HubAccent)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(suggestion.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(suggestion.reason, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = "Search suggestion", tint = HubAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeLibraryHeader(
    loading: Boolean,
    status: String,
    onLoadPlaylists: () -> Unit,
    onLoadLiked: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = HubPanel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YOUR YOUTUBE LIBRARY", color = HubAccent, style = MaterialTheme.typography.labelLarge)
            Text("Browse your account playlists or the videos you've liked, using the official YouTube Data API.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onLoadPlaylists,
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = HubAccent, contentColor = HubBackground)
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HubBackground)
                else Icon(Icons.Default.MusicNote, contentDescription = null)
                Text("  Load my YouTube playlists")
            }
            Button(
                onClick = onLoadLiked,
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF343840), contentColor = Color.White)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("  Load my liked videos")
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
    busy: Boolean,
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
                    enabled = !busy && (connected || configured || title == "YouTube"),
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
                    if (title == "Spotify") "Spotify connection isn't available yet. Please try again later." else "YouTube connection isn't available yet. Please try again later.",
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
