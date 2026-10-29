package com.vedansh.unifiedmusic

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.vedansh.unifiedmusic.catalog.DemoCatalog
import com.vedansh.unifiedmusic.catalog.LocalMusicRepository
import com.vedansh.unifiedmusic.catalog.Track
import com.vedansh.unifiedmusic.playback.MusicPlaybackService

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
    val catalog = remember { DemoCatalog() }
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Home") }
    var localTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var controllerFuture by remember { mutableStateOf<ListenableFuture<MediaController>?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    var nowPlayingTitle by remember { mutableStateOf<String?>(null) }
    var nowPlayingArtist by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    fun refreshLocalTracks() {
        localTracks = repository.getTracks()
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
    }

    DisposableEffect(context) {
        val token = SessionToken(
            context,
            ComponentName(context, MusicPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { controller = it }
            },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
            controllerFuture = null
        }
    }

    DisposableEffect(controller) {
        val activeController = controller
        if (activeController == null) {
            onDispose { }
        } else {
            fun syncPlaybackState() {
                val metadata = activeController.mediaMetadata
                nowPlayingTitle = metadata.title?.toString()
                nowPlayingArtist = metadata.artist?.toString()
                isPlaying = activeController.isPlaying
            }
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    syncPlaybackState()
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    syncPlaybackState()
                }
            }
            activeController.addListener(listener)
            syncPlaybackState()
            onDispose { activeController.removeListener(listener) }
        }
    }

    val results = remember(query, localTracks) {
        if (query.isBlank()) emptyList()
        else localTracks.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                (it.album?.contains(query, ignoreCase = true) == true)
        } + catalog.search(query)
    }

    fun playLocalTrack(track: Track) {
        val activeController = controller ?: return
        val queue = localTracks.map { item ->
            MediaItem.Builder()
                .setUri(item.providerId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .build()
                )
                .build()
        }
        val startIndex = localTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        if (queue.isEmpty()) return
        activeController.setMediaItems(queue, startIndex, C.TIME_UNSET)
        activeController.prepare()
        activeController.play()
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink, surface = Panel, primary = Accent,
            onPrimary = Ink, onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                Column {
                    if (nowPlayingTitle != null) {
                        Surface(color = Panel, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(nowPlayingTitle ?: "Nothing playing", fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(nowPlayingArtist ?: "Local library", color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                                    Icon(Icons.Default.SkipPrevious, "Previous")
                                }
                                IconButton(onClick = {
                                    controller?.let { player ->
                                        if (player.isPlaying) player.pause() else player.play()
                                    }
                                }) {
                                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", tint = Accent)
                                }
                                IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                                    Icon(Icons.Default.SkipNext, "Next")
                                }
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
                                        }, contentDescription = tab
                                    )
                                },
                                label = { Text(tab) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(24.dp))
                Text("YOUR MUSIC, ONE PLACE", color = Accent, style = MaterialTheme.typography.labelMedium)
                Text("Unified", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text(
                    when (selectedTab) {
                        "Library" -> "Music stored on this device."
                        "Search" -> "Search your device library and demo catalogue."
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

                if (selectedTab == "Search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Songs, artists, albums…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "Clear")
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
                    if (query.isBlank()) {
                        EmptyState("Search your music", "Find local tracks by title, artist or album.")
                    } else if (results.isEmpty()) {
                        EmptyState("No matches", "Try another song or artist.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(results, key = { it.id }) { track ->
                                TrackRow(track = track, onClick = {
                                    if (track.source == com.vedansh.unifiedmusic.catalog.TrackSource.LOCAL) {
                                        playLocalTrack(track)
                                    }
                                })
                            }
                        }
                    }
                } else if (selectedTab == "Library") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ON THIS DEVICE", color = Color.LightGray, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { refreshLocalTracks() }) { Text("Refresh", color = Accent) }
                    }
                    if (localTracks.isEmpty()) {
                        EmptyState("Your library is empty", "Add audio files to your device, then refresh.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(localTracks, key = { it.id }) { track ->
                                TrackRow(track = track, onClick = { playLocalTrack(track) })
                            }
                        }
                    }
                } else {
                    Text("ON THIS DEVICE", color = Color.LightGray, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    if (localTracks.isEmpty()) {
                        EmptyState("Start with your own music", "Allow audio access to scan songs stored on this device.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(localTracks.take(8), key = { it.id }) { track ->
                                TrackRow(track = track, onClick = { playLocalTrack(track) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).background(Color(0xFF30343B), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.SemiBold)
                Text(track.artist, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            if (track.source == com.vedansh.unifiedmusic.catalog.TrackSource.LOCAL) {
                Icon(Icons.Default.PlayArrow, "Play", tint = Accent)
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
