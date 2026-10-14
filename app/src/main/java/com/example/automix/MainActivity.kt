package com.example.automix

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

// Modelo exclusivo de la UI. Se llama UiTrack para no chocar con
// el data class Track definido en AutomixService.kt
data class UiTrack(
    val title: String,
    val artist: String,
    val bpm: String,
    val key: String,
    val genre: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutomixTheme {
                AutomixApp()
            }
        }
    }
}

@Composable
fun AutomixTheme(content: @Composable () -> Unit) {
    val LightColorScheme = lightColorScheme(
        primary = Color(0xFF2E6F40),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8F3DC),
        onPrimaryContainer = Color(0xFF1B4332),
        background = Color(0xFFF8F9FA),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFE9ECEF)
    )
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomixApp() {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedMode by remember { mutableStateOf("Aleatorio") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "AUTOMIX",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                actions = {
                    IconButton(onClick = { selectedTab = 2 }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Reproductor") },
                    label = { Text("Reproductor") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Mashup") },
                    label = { Text("Mashup") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                    label = { Text("Ajustes") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> MainReproducerScreen(selectedMode, onModeChange = { selectedMode = it })
                1 -> MashupScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun MainReproducerScreen(selectedMode: String, onModeChange: (String) -> Unit) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.parse("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"))
            setMediaItem(mediaItem)
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    val tracks = listOf(
        UiTrack("Midnight Vibes", "The Sunset Riders", "128 BPM", "C#m", "Pop"),
        UiTrack("Better Days", "Luna Coast", "122 BPM", "G", "Pop"),
        UiTrack("Electric Heart", "Neon Waves", "126 BPM", "Em", "Electronic"),
        UiTrack("Lost in Time", "The Paper Kings", "124 BPM", "Bm", "Indie")
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(modifier = Modifier.weight(1.8f), verticalArrangement = Arrangement.Center) {
                            Text("Midnight Vibes", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("The Sunset Riders", fontSize = 14.sp, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text("128 BPM", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text("Key: C#m", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text("Pop", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Column {
                        LinearProgressIndicator(
                            progress = 0.35f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1:42", fontSize = 12.sp, color = Color.Gray)
                            Text("3:45", fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {}) { Icon(Icons.Default.Shuffle, contentDescription = "Aleatorio", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = {}) { Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior") }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        ) {
                            IconButton(onClick = {
                                if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                                isPlaying = !isPlaying
                            }, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        IconButton(onClick = {}) { Icon(Icons.Default.SkipNext, contentDescription = "Siguiente") }
                        IconButton(onClick = {}) { Icon(Icons.Default.Repeat, contentDescription = "Repetir") }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeButton(
                    title = "Aleatorio",
                    icon = Icons.Default.Shuffle,
                    selected = selectedMode == "Aleatorio",
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange("Aleatorio") }
                )
                ModeButton(
                    title = "Smart",
                    icon = Icons.Default.Psychology,
                    selected = selectedMode == "Smart",
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange("Smart") }
                )
                ModeButton(
                    title = "Género",
                    icon = Icons.Default.Label,
                    selected = selectedMode == "Género",
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange("Género") }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("Biblioteca (compacta)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                TextButton(onClick = {}) {
                    Text("Ver todo >", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        items(tracks) { track ->
            TrackItem(track = track)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun ModeButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        modifier = modifier
            .height(80.dp)
            .clickable(onClick = onClick),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = contentColor)
        }
    }
}

@Composable
fun TrackItem(track: UiTrack) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(track.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(track.artist, fontSize = 12.sp, color = Color.Gray)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(track.bpm, fontSize = 12.sp, color = Color.Gray)
                Text(track.key, fontSize = 12.sp, color = Color.Gray)
                IconButton(onClick = {}, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun MashupScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Vista de Mashup y Mezcla", fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Ajustes y análisis en segundo plano", fontWeight = FontWeight.Bold, fontSize = 20.sp)

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Transición: Desvanecer", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Reproducir 70% de cada canción", fontSize = 14.sp, color = Color.Gray)
                LinearProgressIndicator(progress = 0.7f, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(8.dp))
                Text("Transición: 8 segundos", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                LinearProgressIndicator(progress = 0.4f, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
