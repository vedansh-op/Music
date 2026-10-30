package com.vedansh.unifiedmusic

import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.vedansh.unifiedmusic.catalog.AudiusCatalog
import com.vedansh.unifiedmusic.catalog.FavoritesRepository
import com.vedansh.unifiedmusic.catalog.LocalMusicRepository
import com.vedansh.unifiedmusic.catalog.LocalPlaylist
import com.vedansh.unifiedmusic.catalog.LocalPlaylistsRepository
import com.vedansh.unifiedmusic.catalog.Track
import com.vedansh.unifiedmusic.catalog.TrackSource
import com.vedansh.unifiedmusic.playback.MusicPlaybackService
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Ink = Color(0xFF101114)
private val Panel = Color(0xFF1B1D22)
private val Accent = Color(0xFFB7F36B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UnifiedMusicApp() }
    }
}

@Composable
private fun UnifiedMusicApp() {
    val context = LocalContext.current
    val repository = remember { LocalMusicRepository(context.applicationContext) }
    val catalog = remember { AudiusCatalog() }
    val favoritesRepository = remember { FavoritesRepository(context.applicationContext) }
    val favoriteIds by favoritesRepository.favoriteTrackIds.collectAsState(initial = emptySet())
    val playlistsRepository = remember { LocalPlaylistsRepository(context.applicationContext) }
    val playlists by playlistsRepository.playlists.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Home") }
    var libraryFilter by remember { mutableStateOf("All") }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var pendingTrackForPlaylist by remember { mutableStateOf<Track?>(null) }
    var playlistToDelete by remember { mutableStateOf<LocalPlaylist?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var localTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var queueItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var currentQueueIndex by remember { mutableIntStateOf(0) }
    var showQueueScreen by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var isLoadingLibrary by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var onlineTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var onlineSearchError by remember { mutableStateOf<String?>(null) }
    var featuredTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoadingFeatured by remember { mutableStateOf(false) }
    var featuredError by remember { mutableStateOf<String?>(null) }
    var nowPlayingTitle by remember { mutableStateOf<String?>(null) }
    var nowPlayingArtist by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var nowPlayingSourceLabel by remember { mutableStateOf("On-device music") }
    var nowPlayingSourceUrl by remember { mutableStateOf<String?>(null) }
    var nowPlayingArtworkUri by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }

    fun refreshLocalTracks() {
        if (!repository.hasReadPermission()) return
        scope.launch {
            isLoadingLibrary = true
            libraryError = null
            try {
                localTracks = repository.getTracks()
            } catch (_: Exception) {
                libraryError = "Couldn't scan audio files. Check storage permission and try again."
            } finally {
                isLoadingLibrary = false
            }
        }
    }

    fun toggleFavorite(track: Track) {
        if (track.source != TrackSource.LOCAL) return
        scope.launch {
            favoritesRepository.setFavorite(track.id, track.id !in favoriteIds)
        }
    }

    fun createPlaylist() {
        val cleanName = newPlaylistName.trim()
        if (cleanName.isBlank()) return
        scope.launch {
            val playlistId = playlistsRepository.createPlaylist(cleanName)
            if (playlistId != null) {
                pendingTrackForPlaylist?.let { track ->
                    playlistsRepository.setTrackInPlaylist(playlistId, track.id, true)
                }
                selectedPlaylistId = playlistId
                libraryFilter = "Playlists"
                selectedTab = "Library"
                showCreatePlaylistDialog = false
                newPlaylistName = ""
                pendingTrackForPlaylist = null
            }
        }
    }

    fun addTrackToPlaylist(track: Track, playlistId: String) {
        scope.launch {
            playlistsRepository.setTrackInPlaylist(playlistId, track.id, true)
        }
        pendingTrackForPlaylist = null
    }

    fun refreshFeatured() {
        scope.launch {
            isLoadingFeatured = true
            featuredError = null
            try {
                featuredTracks = catalog.trending()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                featuredError = "Couldn't load Audius trending right now. Check your connection and retry."
            } finally {
                isLoadingFeatured = false
            }
        }
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionDenied = !granted
        if (granted) refreshLocalTracks()
    }

    LaunchedEffect(Unit) {
        if (repository.hasReadPermission()) refreshLocalTracks()
        refreshFeatured()
    }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        var disposed = false
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { builtController ->
                    if (!disposed) controller = builtController
                }
            },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            disposed = true
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    DisposableEffect(controller) {
        val activeController = controller
        if (activeController == null) {
            onDispose { }
        } else {
            fun syncPlaybackState() {
                val metadata = activeController.mediaMetadata
                nowPlayingTitle = metadata.title?.toString()?.takeIf { it.isNotBlank() }
                nowPlayingArtist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
                nowPlayingArtworkUri = metadata.artworkUri?.toString()
                nowPlayingSourceUrl = activeController.currentMediaItem?.requestMetadata?.mediaUri?.toString()
                nowPlayingSourceLabel = if (activeController.currentMediaItem?.mediaId?.startsWith("audius:") == true) {
                    "Audius streaming"
                } else {
                    "On-device music"
                }
                isPlaying = activeController.isPlaying
                playbackPositionMs = activeController.currentPosition.coerceAtLeast(0L)
                val currentDuration = activeController.duration
                durationMs = if (currentDuration != C.TIME_UNSET && currentDuration > 0L) currentDuration else 0L
                shuffleEnabled = activeController.shuffleModeEnabled
                repeatMode = activeController.repeatMode
                queueItems = List(activeController.mediaItemCount) { index ->
                    activeController.getMediaItemAt(index)
                }
                currentQueueIndex = activeController.currentMediaItemIndex.coerceAtLeast(0)
            }

            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    playbackError = null
                    syncPlaybackState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val isAudiusTrack = activeController.currentMediaItem?.mediaId?.startsWith("audius:") == true
                    playbackError = if (isAudiusTrack) {
                        "This Audius stream couldn't be played. Try another result or open its original track."
                    } else {
                        "This track couldn't be played. Check the file and try another track."
                    }
                    isPlaying = false
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    syncPlaybackState()
                }

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int
                ) {
                    syncPlaybackState()
                }

                override fun onRepeatModeChanged(mode: Int) {
                    repeatMode = mode
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    shuffleEnabled = shuffleModeEnabled
                }
            }
            activeController.addListener(listener)
            syncPlaybackState()
            onDispose { activeController.removeListener(listener) }
        }
    }

    LaunchedEffect(controller, showNowPlaying, isScrubbing) {
        val activeController = controller ?: return@LaunchedEffect
        while (showNowPlaying) {
            if (!isScrubbing) {
                playbackPositionMs = activeController.currentPosition.coerceAtLeast(0L)
                val currentDuration = activeController.duration
                durationMs = if (currentDuration != C.TIME_UNSET && currentDuration > 0L) currentDuration else 0L
            }
            delay(500L)
        }
    }

    BackHandler(enabled = showNowPlaying) {
        if (showQueueScreen) {
            showQueueScreen = false
        } else {
            showNowPlaying = false
        }
    }

    LaunchedEffect(query, selectedTab) {
        if (selectedTab != "Search" || query.isBlank()) {
            onlineTracks = emptyList()
            onlineSearchError = null
            isSearchingOnline = false
        } else {
            onlineTracks = emptyList()
            isSearchingOnline = true
            onlineSearchError = null
            delay(350L)
            try {
                onlineTracks = catalog.search(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onlineTracks = emptyList()
                onlineSearchError = "Audius search couldn't connect. Your local results are still available."
            } finally {
                if (currentCoroutineContext().isActive) {
                    isSearchingOnline = false
                }
            }
        }
    }

    val results = remember(query, localTracks, onlineTracks) {
        if (query.isBlank()) emptyList()
        else localTracks.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                (it.album?.contains(query, ignoreCase = true) == true)
        } + onlineTracks
    }
    val visibleLibraryTracks = remember(localTracks, favoriteIds, libraryFilter) {
        if (libraryFilter == "Liked") localTracks.filter { it.id in favoriteIds } else localTracks
    }
    val selectedPlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
    val selectedPlaylistTracks = remember(localTracks, selectedPlaylist) {
        val ids = selectedPlaylist?.trackIds ?: emptySet()
        localTracks.filter { it.id in ids }
    }

    fun playTrack(
        track: Track,
        queueSource: List<Track> = if (track.source == TrackSource.AUDIUS) onlineTracks else localTracks
    ) {
        val activeController = controller ?: return
        playbackError = null
        val playableTracks = queueSource.filter {
            it.source == TrackSource.LOCAL || (it.source == TrackSource.AUDIUS && !it.streamUrl.isNullOrBlank())
        }
        val queue = playableTracks.map { item ->
            val mediaUri = if (item.source == TrackSource.LOCAL) item.providerId else item.streamUrl.orEmpty()
            MediaItem.Builder()
                .setMediaId(item.id)
                .setUri(mediaUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album ?: if (item.source == TrackSource.AUDIUS) "Audius" else null)
                        .setArtworkUri(item.artworkUrl?.let(Uri::parse))
                        .build()
                )
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(item.sourceUrl?.let(Uri::parse))
                        .build()
                )
                .build()
        }
        val startIndex = playableTracks.indexOfFirst { it.id == track.id }
        if (queue.isEmpty() || startIndex < 0) return
        activeController.setMediaItems(queue, startIndex, C.TIME_UNSET)
        activeController.prepare()
        activeController.play()
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface = Panel,
            primary = Accent,
            onPrimary = Ink,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                if (!showNowPlaying) {
                    Column {
                        if (nowPlayingTitle != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Panel)
                                    .clickable { showNowPlaying = true }
                                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(42.dp)
                                        .background(Color(0xFF30343B), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!nowPlayingArtworkUri.isNullOrBlank()) {
                                        AsyncImage(
                                            model = nowPlayingArtworkUri,
                                            contentDescription = "Current album artwork",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            error = rememberVectorPainter(Icons.Default.MusicNote),
                                            fallback = rememberVectorPainter(Icons.Default.MusicNote)
                                        )
                                    } else {
                                        Icon(Icons.Default.MusicNote, null, tint = Accent)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        nowPlayingTitle.orEmpty(),
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        nowPlayingArtist ?: "On-device audio",
                                        color = Color.LightGray,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                                    Icon(Icons.Default.SkipPrevious, "Previous")
                                }
                                IconButton(onClick = {
                                    controller?.let { player ->
                                        if (player.isPlaying) player.pause() else player.play()
                                    }
                                }) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        if (isPlaying) "Pause" else "Play",
                                        tint = Accent
                                    )
                                }
                                IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                                    Icon(Icons.Default.SkipNext, "Next")
                                }
                            }
                        }
                        NavigationBar(containerColor = Panel) {
                            listOf("Home", "Search", "Library").forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Icon(
                                            when (tab) {
                                                "Home" -> Icons.Default.Home
                                                "Library" -> Icons.Default.LibraryMusic
                                                else -> Icons.Default.Search
                                            },
                                            contentDescription = tab
                                        )
                                    },
                                    label = { Text(tab) }
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            if (showNowPlaying) {
                if (showQueueScreen) {
                    QueueScreen(
                        queueItems = queueItems,
                        currentQueueIndex = currentQueueIndex,
                        onBack = { showQueueScreen = false },
                        onSelect = { index ->
                            controller?.seekTo(index, C.TIME_UNSET)
                            controller?.play()
                            showQueueScreen = false
                        },
                        modifier = Modifier.padding(padding)
                    )
                } else {
                    NowPlayingScreen(
                        title = nowPlayingTitle ?: "Nothing playing",
                        artist = nowPlayingArtist ?: "Choose a track from your library",
                        artworkUri = nowPlayingArtworkUri,
                        sourceLabel = nowPlayingSourceLabel,
                        sourceUrl = nowPlayingSourceUrl,
                        playbackError = playbackError,
                        onRetryPlayback = {
                            controller?.prepare()
                            controller?.play()
                            playbackError = null
                        },
                        isPlaying = isPlaying,
                        positionMs = playbackPositionMs,
                        durationMs = durationMs,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        queueCount = queueItems.size,
                        onShowQueue = { showQueueScreen = true },
                        onBack = {
                            showQueueScreen = false
                            showNowPlaying = false
                        },
                        onSeekPreview = {
                            playbackPositionMs = it
                            isScrubbing = true
                        },
                        onSeekFinished = {
                            controller?.seekTo(playbackPositionMs)
                            isScrubbing = false
                        },
                        onPrevious = { controller?.seekToPreviousMediaItem() },
                        onPlayPause = {
                            controller?.let { player ->
                                if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                        onNext = { controller?.seekToNextMediaItem() },
                        onShuffle = {
                            controller?.let { player -> player.shuffleModeEnabled = !player.shuffleModeEnabled }
                        },
                        onRepeat = {
                            controller?.let { player ->
                                player.repeatMode = when (player.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text("YOUR MUSIC, ONE PLACE", color = Accent, style = MaterialTheme.typography.labelMedium)
                    Text("Unified", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    Text(
                        when (selectedTab) {
                            "Library" -> "Music stored on this device."
                            "Search" -> "Search your device and streamable Audius tracks."
                            else -> "Your personal music space."
                        },
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(18.dp))

                    if (!repository.hasReadPermission()) {
                        Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("Enable your music library", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Allow audio access to find music stored on this device.",
                                    color = Color.LightGray
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { permissionLauncher.launch(permission) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)
                                ) { Text("Allow access") }
                                if (permissionDenied) {
                                    Text(
                                        "Permission wasn't granted. You can enable it in Android app settings.",
                                        color = Color(0xFFFFB4AB),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    if (!libraryError.isNullOrBlank()) {
                        Text(libraryError.orEmpty(), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }

                    when (selectedTab) {
                        "Search" -> {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Songs, artists, albums…") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Default.Close, "Clear")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Color.DarkGray,
                                    cursorColor = Accent
                                )
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("SEARCH RESULTS", color = Color.LightGray, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            if (onlineSearchError != null) {
                                Text(
                                    onlineSearchError.orEmpty(),
                                    color = Color(0xFFFFB4AB),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (query.isBlank()) {
                                EmptyState("Search your music", "Find local tracks and streamable tracks on Audius.")
                            } else {
                                if (isSearchingOnline) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text("Searching Audius…", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (results.isEmpty() && !isSearchingOnline) {
                                    EmptyState("No matches", "Try another song or artist.")
                                } else if (results.isNotEmpty()) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(results, key = { it.id }) { track ->
                                            TrackRow(
                                                track = track,
                                                onClick = {
                                                    when (track.source) {
                                                        TrackSource.LOCAL -> playTrack(track)
                                                        TrackSource.AUDIUS -> playTrack(track, onlineTracks)
                                                        else -> Unit
                                                    }
                                                },
                                                isFavorite = track.id in favoriteIds,
                                                onFavoriteClick = if (track.source == TrackSource.LOCAL) ({ toggleFavorite(track) }) else null,
                                                onAddToPlaylist = if (track.source == TrackSource.LOCAL) ({ pendingTrackForPlaylist = track }) else null
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        "Library" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (libraryFilter == "Playlists") "YOUR PLAYLISTS" else "ON THIS DEVICE",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(Modifier.weight(1f))
                                if (isLoadingLibrary) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Accent,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                TextButton(onClick = { refreshLocalTracks() }) { Text("Refresh", color = Accent) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = libraryFilter == "All",
                                    onClick = { libraryFilter = "All"; selectedPlaylistId = null },
                                    label = { Text("All songs") }
                                )
                                FilterChip(
                                    selected = libraryFilter == "Liked",
                                    onClick = { libraryFilter = "Liked"; selectedPlaylistId = null },
                                    leadingIcon = { Icon(Icons.Default.Favorite, null) },
                                    label = { Text("Liked") }
                                )
                                FilterChip(
                                    selected = libraryFilter == "Playlists",
                                    onClick = { libraryFilter = "Playlists"; selectedPlaylistId = null },
                                    leadingIcon = { Icon(Icons.Default.QueueMusic, null) },
                                    label = { Text("Playlists") }
                                )
                            }
                            Spacer(Modifier.height(8.dp))

                            if (libraryFilter == "Playlists") {
                                if (selectedPlaylist != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { selectedPlaylistId = null }) {
                                            Icon(Icons.Default.ArrowBack, "Back to playlists")
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(selectedPlaylist.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(selectedPlaylistTracks.size.toString() + " tracks", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                        }
                                        IconButton(onClick = { playlistToDelete = selectedPlaylist }) {
                                            Icon(Icons.Default.DeleteOutline, "Delete playlist", tint = Color(0xFFFFB4AB))
                                        }
                                    }
                                    if (selectedPlaylistTracks.isEmpty()) {
                                        EmptyState("Playlist is empty", "Use the playlist button on any local track to add songs.")
                                    } else {
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(selectedPlaylistTracks, key = { it.id }) { track ->
                                                TrackRow(
                                                    track = track,
                                                    onClick = { playTrack(track, selectedPlaylistTracks) },
                                                    isFavorite = track.id in favoriteIds,
                                                    onFavoriteClick = { toggleFavorite(track) },
                                                    onAddToPlaylist = { pendingTrackForPlaylist = track },
                                                    onRemoveFromPlaylist = {
                                                        scope.launch {
                                                            playlistsRepository.setTrackInPlaylist(selectedPlaylist.id, track.id, false)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(playlists.size.toString() + " playlists", color = Color.LightGray, style = MaterialTheme.typography.labelLarge)
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = {
                                            newPlaylistName = ""
                                            pendingTrackForPlaylist = null
                                            showCreatePlaylistDialog = true
                                        }) {
                                            Icon(Icons.Default.Add, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("New playlist", color = Accent)
                                        }
                                    }
                                    if (playlists.isEmpty()) {
                                        EmptyState("No playlists yet", "Create a playlist to organize the music on your device.")
                                    } else {
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(playlists, key = { it.id }) { playlist ->
                                                Surface(
                                                    color = Panel,
                                                    shape = RoundedCornerShape(16.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .clickable { selectedPlaylistId = playlist.id }
                                                            .padding(14.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(Icons.Default.QueueMusic, null, tint = Accent, modifier = Modifier.size(36.dp))
                                                        Spacer(Modifier.width(12.dp))
                                                        Column(Modifier.weight(1f)) {
                                                            Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            Text(playlist.trackIds.size.toString() + " tracks", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                                        }
                                                        IconButton(onClick = { playlistToDelete = playlist }) {
                                                            Icon(Icons.Default.DeleteOutline, "Delete " + playlist.name, tint = Color(0xFFFFB4AB))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (localTracks.isEmpty() && isLoadingLibrary) {
                                LoadingState("Scanning your music library…")
                            } else if (visibleLibraryTracks.isEmpty()) {
                                if (libraryFilter == "Liked") {
                                    EmptyState("No liked songs", "Tap the heart on a track to keep it here.")
                                } else {
                                    EmptyState("Your library is empty", "Add audio files to your device, then refresh.")
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(visibleLibraryTracks, key = { it.id }) { track ->
                                        TrackRow(
                                            track = track,
                                            onClick = { playTrack(track) },
                                            isFavorite = track.id in favoriteIds,
                                            onFavoriteClick = { toggleFavorite(track) },
                                            onAddToPlaylist = { pendingTrackForPlaylist = track }
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = "audius-trending-heading") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("TRENDING ON AUDIUS", color = Color.LightGray, style = MaterialTheme.typography.labelLarge)
                                            Text("Discover something new", color = Accent, style = MaterialTheme.typography.bodySmall)
                                        }
                                        IconButton(
                                            enabled = !isLoadingFeatured,
                                            onClick = { refreshFeatured() }
                                        ) {
                                            Icon(Icons.Default.Refresh, "Refresh trending", tint = Accent)
                                        }
                                        if (isLoadingFeatured) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                color = Accent,
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                }
                                if (featuredTracks.isEmpty()) {
                                    item(key = "audius-trending-empty") {
                                        Text(
                                            featuredError ?: if (isLoadingFeatured) "Loading trending music…" else "No trending tracks are available right now.",
                                            color = Color.LightGray,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                } else {
                                    items(featuredTracks, key = { it.id }) { track ->
                                        TrackRow(
                                            track = track,
                                            onClick = { playTrack(track, featuredTracks) }
                                        )
                                    }
                                }

                                item(key = "local-library-heading") {
                                    Text("ON THIS DEVICE", color = Color.LightGray, style = MaterialTheme.typography.labelLarge)
                                }
                                if (localTracks.isEmpty() && isLoadingLibrary) {
                                    item(key = "local-library-loading") { LoadingState("Scanning your music library…") }
                                } else if (localTracks.isEmpty()) {
                                    item(key = "local-library-empty") {
                                        EmptyState("Start with your own music", "Allow audio access to scan songs stored on this device.")
                                    }
                                } else {
                                    items(localTracks.take(8), key = { it.id }) { track ->
                                        TrackRow(
                                            track = track,
                                            onClick = { playTrack(track) },
                                            isFavorite = track.id in favoriteIds,
                                            onFavoriteClick = { toggleFavorite(track) },
                                            onAddToPlaylist = { pendingTrackForPlaylist = track }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    if (pendingTrackForPlaylist != null && !showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { pendingTrackForPlaylist = null },
            title = { Text("Add to playlist") },
            text = {
                Column {
                    Text(pendingTrackForPlaylist?.title.orEmpty(), color = Color.LightGray)
                    Spacer(Modifier.height(8.dp))
                    if (playlists.isEmpty()) {
                        Text("No playlists yet. Create one to save this song.")
                    } else {
                        playlists.forEach { playlist ->
                            TextButton(
                                onClick = {
                                    pendingTrackForPlaylist?.let { track -> addTrackToPlaylist(track, playlist.id) }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(playlist.name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    newPlaylistName = ""
                    showCreatePlaylistDialog = true
                }) { Text("New playlist") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTrackForPlaylist = null }) { Text("Cancel") }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Create playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it.take(60) },
                    label = { Text("Playlist name") },
                    placeholder = { Text("Late-night drive") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newPlaylistName.isNotBlank(),
                    onClick = { createPlaylist() }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreatePlaylistDialog = false
                    pendingTrackForPlaylist = null
                }) { Text("Cancel") }
            }
        )
    }

    if (playlistToDelete != null) {
        val deleting = playlistToDelete
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete playlist?") },
            text = { Text("Delete \"" + (deleting?.name ?: "") + "\"? The songs themselves will stay on your device.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting?.let { playlist ->
                        scope.launch {
                            playlistsRepository.deletePlaylist(playlist.id)
                        }
                        if (selectedPlaylistId == playlist.id) selectedPlaylistId = null
                    }
                    playlistToDelete = null
                }) { Text("Delete", color = Color(0xFFFFB4AB)) }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) { Text("Cancel") }
            }
        )
    }

    }
}

@Composable
private fun NowPlayingScreen(
    title: String,
    artist: String,
    artworkUri: String?,
    sourceLabel: String,
    sourceUrl: String?,
    playbackError: String?,
    onRetryPlayback: () -> Unit,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    queueCount: Int,
    onShowQueue: () -> Unit,
    onBack: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowDown, "Minimize player")
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("NOW PLAYING", color = Accent, style = MaterialTheme.typography.labelMedium)
                Text(sourceLabel, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShowQueue) {
                Icon(Icons.Default.QueueMusic, "Open queue (" + queueCount + " tracks)")
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFF252931), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = "Album artwork",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    error = rememberVectorPainter(Icons.Default.MusicNote),
                    fallback = rememberVectorPainter(Icons.Default.MusicNote)
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = "Album artwork placeholder",
                    tint = Accent,
                    modifier = Modifier.size(100.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(artist, style = MaterialTheme.typography.bodyLarge, color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!sourceUrl.isNullOrBlank()) {
            TextButton(onClick = { uriHandler.openUri(sourceUrl) }) {
                Text("Open original track on Audius", color = Accent)
            }
        }
        if (!playbackError.isNullOrBlank()) {
            Text(
                playbackError,
                color = Color(0xFFFFB4AB),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onRetryPlayback) {
                Text("Retry playback", color = Accent)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (durationMs > 0L) {
            Slider(
                value = positionMs.coerceIn(0L, durationMs).toFloat(),
                onValueChange = { onSeekPreview(it.toLong().coerceIn(0L, durationMs)) },
                onValueChangeFinished = onSeekFinished,
                valueRange = 0f..durationMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Color.DarkGray
                )
            )
        } else {
            Slider(
                value = 0f,
                onValueChange = {},
                enabled = false,
                valueRange = 0f..1f
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(positionMs), color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            Text(formatPlaybackTime(durationMs), color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffleEnabled) Accent else Color.LightGray)
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Default.SkipPrevious, "Previous track", modifier = Modifier.size(34.dp))
            }
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Ink)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp)
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, "Next track", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = onRepeat) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    when (repeatMode) {
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        else -> "Repeat off"
                    },
                    tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.LightGray else Accent
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun QueueScreen(
    queueItems: List<MediaItem>,
    currentQueueIndex: Int,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back to player")
            }
            Column(Modifier.weight(1f)) {
                Text("PLAY QUEUE", color = Accent, style = MaterialTheme.typography.labelMedium)
                Text(queueItems.size.toString() + " tracks", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (queueItems.isEmpty()) {
            EmptyState("Queue is empty", "Play a track from your device library to build the queue.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(queueItems, key = { index, _ -> index }) { index, item ->
                    val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown title"
                    val artist = item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                    Surface(
                        color = if (index == currentQueueIndex) Panel else Ink,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .background(Color(0xFF30343B), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                val art = item.mediaMetadata.artworkUri
                                if (art != null) {
                                    AsyncImage(
                                        model = art,
                                        contentDescription = title + " artwork",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        error = rememberVectorPainter(Icons.Default.MusicNote),
                                        fallback = rememberVectorPainter(Icons.Default.MusicNote)
                                    )
                                } else {
                                    Icon(
                                        if (index == currentQueueIndex) Icons.Default.Equalizer else Icons.Default.MusicNote,
                                        null,
                                        tint = if (index == currentQueueIndex) Accent else Color.LightGray
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(artist, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (index == currentQueueIndex) {
                                Text("PLAYING", color = Accent, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    isFavorite: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).background(Color(0xFF30343B), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (!track.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title + " artwork",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = rememberVectorPainter(Icons.Default.MusicNote),
                        fallback = rememberVectorPainter(Icons.Default.MusicNote)
                    )
                } else {
                    Icon(Icons.Default.MusicNote, null, tint = Accent)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (track.source == TrackSource.LOCAL) {
                if (onAddToPlaylist != null) {
                    IconButton(onClick = onAddToPlaylist) {
                        Icon(Icons.Default.PlaylistAdd, "Add to playlist", tint = Color.LightGray)
                    }
                }
                if (onRemoveFromPlaylist != null) {
                    IconButton(onClick = onRemoveFromPlaylist) {
                        Icon(Icons.Default.RemoveCircleOutline, "Remove from playlist", tint = Color.LightGray)
                    }
                }
                if (onFavoriteClick != null) {
                    IconButton(onClick = onFavoriteClick) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (isFavorite) "Remove from liked songs" else "Add to liked songs",
                            tint = if (isFavorite) Accent else Color.LightGray
                        )
                    }
                }
                Icon(Icons.Default.PlayArrow, "Play", tint = Accent)
            } else if (track.source == TrackSource.AUDIUS) {
                Text("AUDIUS", color = Accent, style = MaterialTheme.typography.labelSmall)
                Icon(Icons.Default.PlayArrow, "Play online track", tint = Accent)
            } else {
                Text("DEMO", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.LibraryMusic, null, tint = Accent, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.LightGray)
    }
}

@Composable
private fun LoadingState(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color.LightGray)
    }
}
