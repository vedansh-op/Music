package com.vedansh.unifiedmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.vedansh.unifiedmusic.catalog.DemoCatalog
import com.vedansh.unifiedmusic.catalog.Track
import com.vedansh.unifiedmusic.catalog.TrackSource

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
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Search") }
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    val catalog = remember { DemoCatalog() }
    val results = remember(query) { catalog.search(query) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink, surface = Panel, primary = Accent,
            onPrimary = Ink, onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
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
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(24.dp))
                Text("YOUR MUSIC, ONE PLACE", color = Accent, style = MaterialTheme.typography.labelMedium)
                Text("Unified", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text("Search across your connected music sources.", color = Color.LightGray)
                Spacer(Modifier.height(22.dp))

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
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceChip("All sources", true)
                    SourceChip("YouTube", false)
                    SourceChip("Spotify", false)
                    SourceChip("On device", false)
                }
                Spacer(Modifier.height(20.dp))
                Text(if (query.isBlank()) "Try a search" else "RESULTS", color = Color.LightGray,
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))

                if (query.isBlank()) {
                    EmptyState()
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(results) { track ->
                            TrackRow(track = track, onClick = { selectedTrack = track })
                        }
                    }
                }

                selectedTrack?.let { track ->
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("SELECTED TRACK", color = Accent, style = MaterialTheme.typography.labelSmall)
                                Text(track.title, fontWeight = FontWeight.Bold)
                                Text("${track.artist} · ${track.source.label}", color = Color.LightGray)
                            }
                            Icon(Icons.Default.PlayArrow, "Playback integration milestone", tint = Accent)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean) {
    Surface(
        color = if (selected) Accent else Panel,
        shape = RoundedCornerShape(50)
    ) {
        Text(label, color = if (selected) Ink else Color.LightGray,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium)
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
            Text(track.source.label, color = Accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.LibraryMusic, null, tint = Accent, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(12.dp))
        Text("A bigger music universe.", fontWeight = FontWeight.Bold)
        Text("Search to see results from connected sources.", color = Color.LightGray)
    }
}
