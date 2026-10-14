package com.example.automix

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore.Audio.Media
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    // --- Constantes de orden ---
    private val SORT_RANDOM = "Aleatorio"
    private val SORT_SMART = "Smart"
    private val SORT_TITLE = "Título"
    private val SORT_ARTIST = "Artista"
    private val SORT_BPM = "BPM"

    // --- Estado ---
    private lateinit var prefs: SharedPreferences
    private var svc: AutomixService? = null
    private var allTracks: List<Track> = emptyList()
    private var filteredTracks: List<Track> = emptyList()
    private var randomRank: Map<String, Int> = emptyMap()
    private val bpmCache = mutableMapOf<String, Int>()
    private val keyCache = mutableMapOf<String, String>()
    @Volatile private var analysisRunning = false

    private var percent = 70
    private var fade = 8
    private var skip = 0
    private var transitionStyle = "Desvanecer"
    private var sortMode = SORT_RANDOM
    private var lastManualSort = SORT_TITLE
    private var selectedFolders: Set<String> = emptySet()
    private var currentGenre: String? = null

    private val uiState = mutableStateOf(UiSnapshot())
    
    private fun pushState(update: (UiSnapshot) -> UiSnapshot) {
    uiState.value = update(uiState.value)
    }
    private val collator: java.text.Collator = java.text.Collator.getInstance()

    data class UiSnapshot(
        val tracks: List<Track> = emptyList(),
        val current: Track? = null,
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val sortMode: String = "Aleatorio",
        val statusText: String = "",
        val analysisText: String = "Biblioteca: esperando permiso para analizar",
        val folders: Set<String> = emptySet(),
        val bpmMap: Map<String, Int> = emptyMap(),
        val keyMap: Map<String, String> = emptyMap(),
        val percent: Int = 70,
        val fadeSec: Int = 8,
        val skipSec: Int = 0,
        val transition: String = "Desvanecer",
        val genre: String? = null,
        val currentBpm: Int? = null,
        val currentKey: String? = null,
        val permissionGranted: Boolean = false
    )

    private val audioPerm: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        } else {
            pushState { it.copy(statusText = "Permiso de música no concedido") }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as AutomixService.LocalBinder).service
            s.playPercent = percent
            s.fadeSec = fade
            s.skipIntroSec = skip
            s.transitionStyle = transitionStyle
            s.onChange = { refreshNow() }
            svc = s
            if (filteredTracks.isNotEmpty()) s.reorderQueue(buildDisplayList())
            refreshNow()
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("automix", MODE_PRIVATE)
        percent = prefs.getInt("percent", 70)
        fade = prefs.getInt("fade", 8)
        skip = prefs.getInt("skip", 0)
        transitionStyle = prefs.getString("transition", "Desvanecer") ?: "Desvanecer"
        sortMode = prefs.getString("sort_mode", SORT_RANDOM) ?: SORT_RANDOM
        lastManualSort = prefs.getString("sort_manual", SORT_TITLE) ?: SORT_TITLE
        selectedFolders = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
        try {
            val bj = JSONObject(prefs.getString("bpm_cache", "{}") ?: "{}")
            bj.keys().forEach { k -> bpmCache[k] = bj.optInt(k) }
            val kj = JSONObject(prefs.getString("key_cache", "{}") ?: "{}")
            kj.keys().forEach { k ->
                kj.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { keyCache[k] = it }
            }
        } catch (_: Exception) {}

        pushState {
            it.copy(
                percent = percent, fadeSec = fade, skipSec = skip,
                transition = transitionStyle, sortMode = sortMode,
                folders = selectedFolders, bpmMap = bpmCache.toMap(), keyMap = keyCache.toMap()
            )
        }

        setContent {
            AutomixTheme {
                AutomixApp(
                    state = uiState.value,
                    onPlayPause = { togglePlayPause() },
                    onNext = { svc?.next() },
                    onPrev = { svc?.previous() },
                    onStop = { svc?.stopAll(); refreshNow() },
                    onPlayTrack = { idx -> playAt(idx) },
                    onStartAutomix = { playAt(0) },
                    onSetSort = { mode -> selectSort(mode) },
                    onSetPercent = { v ->
                        percent = v
                        svc?.playPercent = v
                        prefs.edit().putInt("percent", v).apply()
                        pushState { it.copy(percent = v) }
                    },
                    onSetFade = { v ->
                        fade = v
                        svc?.fadeSec = v
                        prefs.edit().putInt("fade", v).apply()
                        pushState { it.copy(fadeSec = v) }
                    },
                    onSetSkip = { v ->
                        skip = v
                        svc?.skipIntroSec = v
                        prefs.edit().putInt("skip", v).apply()
                        pushState { it.copy(skipSec = v) }
                    },
                    onSetTransition = { style ->
                        transitionStyle = style
                        svc?.transitionStyle = style
                        prefs.edit().putString("transition", style).apply()
                        pushState { it.copy(transition = style) }
                    },
                    onSetFolders = { set ->
                        selectedFolders = set
                        prefs.edit().putStringSet("folders", set).apply()
                        applyFolderFilter()
                    },
                    onDetectBpm = { detectBpmForCurrent() },
                    onDetectKey = { detectKeyForCurrent() },
                    onLookupGenre = { lookupGenreForCurrent() },
                    onMashup = { second -> startMashup(second) },
                    onRequestPermissions = { ensurePermissions() }
                )
            }
        }

        bindService(Intent(this, AutomixService::class.java), conn, BIND_AUTO_CREATE)
        ensurePermissions()
    }

    // ---------------- Permisos y biblioteca ----------------

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) needed += audioPerm
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) loadLibrary() else permLauncher.launch(needed.toTypedArray())
    }

    private fun loadLibrary() {
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) return
        val found = mutableListOf<Track>()
        val proj = arrayOf(Media._ID, Media.TITLE, Media.ARTIST, Media.DURATION, Media.DATA)
        contentResolver.query(
            Media.EXTERNAL_CONTENT_URI, proj,
            "${Media.IS_MUSIC} != 0 AND ${Media.DURATION} >= 60000",
            null, "${Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val iId = c.getColumnIndex(Media._ID)
            val iTitle = c.getColumnIndex(Media.TITLE)
            val iArtist = c.getColumnIndex(Media.ARTIST)
            val iDur = c.getColumnIndex(Media.DURATION)
            val iData = c.getColumnIndex(Media.DATA)
            while (c.moveToNext()) {
                val path = if (iData >= 0) c.getString(iData) else null
                found += Track(
                    ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, c.getLong(iId)),
                    c.getString(iTitle) ?: "Sin título",
                    if (iArtist >= 0) c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: "Artista desconocido"
                    else "Artista desconocido",
                    c.getLong(iDur),
                    path?.let { File(it).parent } ?: "Música"
                )
            }
        }
        allTracks = found
        randomRank = allTracks.shuffled().mapIndexed { i, t -> t.uri.toString() to i }.toMap()
        applyFolderFilter()
        if (found.isEmpty()) {
            pushState { it.copy(statusText = "No encontré canciones en el dispositivo", permissionGranted = true) }
        } else {
            pushState { it.copy(permissionGranted = true) }
            analyzeLibraryInBackground()
        }
    }

    private fun applyFolderFilter() {
        val valid = selectedFolders.filter { f -> allTracks.any { it.folder == f } }.toSet()
        filteredTracks = if (valid.isEmpty()) allTracks else allTracks.filter { it.folder in valid }
        pushState { it.copy(folders = valid) }
        val display = buildDisplayList()
        svc?.reorderQueue(display)
        refreshNow()
    }

    // ---------------- Orden ----------------

    private fun buildDisplayList(): List<Track> = when (sortMode) {
        SORT_RANDOM -> filteredTracks.sortedBy { randomRank[it.uri.toString()] ?: Int.MAX_VALUE }
        SORT_SMART -> smartReorder(filteredTracks.sortedWith(Comparator { a, b -> collator.compare(a.title, b.title) }))
        SORT_ARTIST -> filteredTracks.sortedWith(Comparator { a, b ->
            val ua = a.artist == "Artista desconocido"
            val ub = b.artist == "Artista desconocido"
            if (ua != ub) { if (ua) 1 else -1 }
            else {
                val c = collator.compare(a.artist, b.artist)
                if (c != 0) c else collator.compare(a.title, b.title)
            }
        })
        SORT_BPM -> filteredTracks.sortedWith(Comparator { a, b ->
            val x = bpmCache[a.uri.toString()] ?: Int.MAX_VALUE
            val y = bpmCache[b.uri.toString()] ?: Int.MAX_VALUE
            if (x != y) x.compareTo(y) else collator.compare(a.title, b.title)
        })
        else -> filteredTracks.sortedWith(Comparator { a, b -> collator.compare(a.title, b.title) })
    }

    private fun selectSort(mode: String) {
        sortMode = mode
        if (mode == SORT_RANDOM) randomRank = allTracks.shuffled().mapIndexed { i, t -> t.uri.toString() to i }.toMap()
        if (mode == SORT_TITLE || mode == SORT_ARTIST || mode == SORT_BPM) lastManualSort = mode
        prefs.edit().putString("sort_mode", sortMode).putString("sort_manual", lastManualSort).apply()
        val display = buildDisplayList()
        svc?.reorderQueue(display)
        pushState { it.copy(sortMode = mode) }
        refreshNow()
    }

    private class SmartItem(val track: Track, val bpm: Int?, val key: String?)

    private fun smartReorder(input: List<Track>): List<Track> {
        if (input.size < 3) return input
        val items = input.map { t -> SmartItem(t, bpmCache[t.uri.toString()], keyCache[t.uri.toString()]) }
        val known = items.filter { it.bpm != null || it.key != null }.toMutableList()
        val unknown = items.filter { it.bpm == null && it.key == null }
        if (known.size < 2) return input
        val ordered = ArrayList<Track>(input.size)
        var last = known.removeAt(0)
        ordered.add(last.track)
        while (known.isNotEmpty()) {
            var bestIdx = 0
            var bestCost = Double.MAX_VALUE
            for (i in known.indices) {
                val cost = transitionCost(last, known[i])
                if (cost < bestCost) { bestCost = cost; bestIdx = i }
            }
            last = known.removeAt(bestIdx)
            ordered.add(last.track)
        }
        for (u in unknown) ordered.add(u.track)
        return ordered
    }

    private fun transitionCost(a: SmartItem, b: SmartItem): Double {
        val x = a.bpm; val y = b.bpm
        val bpmCost = if (x != null && y != null)
            minOf(kotlin.math.abs(x - y), kotlin.math.abs(x - y * 2), kotlin.math.abs(x * 2 - y)).toDouble() / 12.0
        else 2.0
        val ka = a.key; val kb = b.key
        val keyCost = if (ka != null && kb != null) if (harmonicallyCompatible(ka, kb)) 0.0 else 3.0
        else 1.2
        return bpmCost + keyCost
    }

    private val camelotMap: Map<String, String> = mapOf(
        "C major" to "8B", "G major" to "9B", "D major" to "10B", "A major" to "11B", "E major" to "12B", "B major" to "1B",
        "F# major" to "2B", "C# major" to "3B", "G# major" to "4B", "D# major" to "5B", "A# major" to "6B", "F major" to "7B",
        "A minor" to "8A", "E minor" to "9A", "B minor" to "10A", "F# minor" to "11A", "C# minor" to "12A", "G# minor" to "1A",
        "D# minor" to "2A", "A# minor" to "3A", "F minor" to "4A", "C minor" to "5A", "G minor" to "6A", "D minor" to "7A"
    )

    private fun harmonicallyCompatible(a: String, b: String): Boolean {
        val x = camelotMap[a] ?: return false
        val y = camelotMap[b] ?: return false
        val xn = x.dropLast(1).toInt(); val yn = y.dropLast(1).toInt()
        if (xn == yn) return true
        val sameMode = x.last() == y.last()
        val neighbor = (xn % 12) + 1 == yn || (yn % 12) + 1 == xn
        return sameMode && neighbor
    }

    // ---------------- Reproducción ----------------

    private fun playAt(index: Int) {
    val display = buildDisplayList()
    if (display.isEmpty()) { 
        Toast.makeText(this, "No hay canciones cargadas", Toast.LENGTH_SHORT).show()
        return 
    }
    startForegroundService(Intent(this, AutomixService::class.java))
    svc?.let {
        it.playPercent = percent; it.fadeSec = fade; it.skipIntroSec = skip
        it.transitionStyle = transitionStyle
        it.startOrdered(display, index.coerceAtLeast(0))
    }
}

private fun reshuffleQueueHot() {
    val s = svc
    if (s?.current == null) {
        Toast.makeText(this, "Reproduce algo primero", Toast.LENGTH_SHORT).show()
        return
    }
    val currentUri = s.current?.uri
    val currentTrack = filteredTracks.firstOrNull { it.uri == currentUri }
    val others = filteredTracks.filter { it.uri != currentUri }.shuffled()
    val newQueue = if (currentTrack != null) listOf(currentTrack) + others else others
    s.reorderQueue(newQueue)
    Toast.makeText(this, "🔀 Cola re-barajada (${others.size} canciones)", Toast.LENGTH_SHORT).show()
    refreshNow()
}
private fun shuffleWithBpmVariety(list: List<Track>): List<Track> {
    if (list.size < 3) return list.shuffled()

    // Agrupar por rango de BPM (bucket de 10 BPM)
    val buckets = mutableMapOf<Int, MutableList<Track>>()
    for (t in list) {
        val bpm = bpmCache[t.uri.toString()] ?: 0
        val bucket = if (bpm == 0) -1 else bpm / 10
        buckets.getOrPut(bucket) { mutableListOf() }.add(t)
    }
    // Barajar dentro de cada bucket
    buckets.values.forEach { it.shuffle() }

    // Alternar entre buckets para evitar monotonía
    val bucketKeys = buckets.keys.filter { it >= 0 }.shuffled().toMutableList()
    val result = mutableListOf<Track>()
    val unknown = buckets[-1]?.toMutableList() ?: mutableListOf()

    while (bucketKeys.isNotEmpty() || unknown.isNotEmpty()) {
        bucketKeys.shuffle()
        val iter = bucketKeys.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            val bucket = buckets[key] ?: continue
            if (bucket.isEmpty()) {
                iter.remove()
            } else {
                result.add(bucket.removeAt(0))
            }
        }
        if (unknown.isNotEmpty() && result.size % 3 == 0) {
            result.add(unknown.removeAt(0))
        }
    }
    result.addAll(unknown)

    // 🎲 Re-barajado local: 15% de intercambios entre adyacentes
    for (i in result.indices) {
        if (i > 0 && i < result.size - 1 && Math.random() < 0.15) {
            val j = (i + 1).coerceAtMost(result.size - 1)
            val tmp = result[i]
            result[i] = result[j]
            result[j] = tmp
        }
    }
    return result
}
    private fun togglePlayPause() {
        val s = svc ?: return
        if (s.current == null && filteredTracks.isNotEmpty()) {
            playAt(0)
        } else {
            s.togglePause()
        }
    }

    private fun startMashup(secondIdx: Int) {
        val display = buildDisplayList()
        if (display.size < 2) { Toast.makeText(this, "Necesitas al menos dos canciones", Toast.LENGTH_SHORT).show(); return }
        val cur = svc?.current
        val firstIdx = display.indexOfFirst { it.uri == cur?.uri }.coerceAtLeast(0)
        if (firstIdx == secondIdx) { Toast.makeText(this, "Elige otra canción", Toast.LENGTH_SHORT).show(); return }
        startForegroundService(Intent(this, AutomixService::class.java))
        svc?.startMashup(display, firstIdx, secondIdx)
    }

    // ---------------- Análisis ----------------

    
            private fun analyzeLibraryInBackground() {
        if (analysisRunning) return
        analysisRunning = true
        Thread {
            val pending = allTracks.filter {
                !bpmCache.containsKey(it.uri.toString()) || !keyCache.containsKey(it.uri.toString())
            }
            var done = 0
            for (t in pending) {
                val id = t.uri.toString()
                try {
                    if (!bpmCache.containsKey(id)) BpmDetector.detect(this, t.uri)?.let { bpmCache[id] = it }
                    if (!keyCache.containsKey(id)) KeyDetector.detect(this, t.uri)?.let { keyCache[id] = it }
                    persistCaches()
                } catch (_: Throwable) {}
                done++
                val d = done
                runOnUiThread {
                    pushState {
                        it.copy(
                            analysisText = "Analizando: $d/${pending.size} · BPM ${bpmCache.size} · tonalidades ${keyCache.size}",
                            bpmMap = bpmCache.toMap(), keyMap = keyCache.toMap()
                        )
                    }
                    refreshNow()
                }
            }
            analysisRunning = false
            runOnUiThread {
                pushState { it.copy(analysisText = "Análisis terminado · BPM ${bpmCache.size} · tonalidades ${keyCache.size}") }
                if (sortMode == SORT_BPM || sortMode == SORT_SMART) {
                    svc?.reorderQueue(buildDisplayList())
                    refreshNow()
                }
            }
        }.start()
    }

    private fun persistCaches() {
        val bj = JSONObject(); bpmCache.forEach { (k, v) -> bj.put(k, v) }
        val kj = JSONObject(); keyCache.forEach { (k, v) -> kj.put(k, v) }
        prefs.edit().putString("bpm_cache", bj.toString()).putString("key_cache", kj.toString()).apply()
    }

    private fun detectBpmForCurrent() {
        val t = svc?.current ?: run { Toast.makeText(this, "Reproduce una canción primero", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val bpm = try { BpmDetector.detect(this, t.uri) } catch (_: Throwable) { null }
            runOnUiThread {
                if (bpm != null) { bpmCache[t.uri.toString()] = bpm; persistCaches() }
                pushState { it.copy(currentBpm = bpm, bpmMap = bpmCache.toMap()) }
                refreshNow()
            }
        }.start()
    }

    private fun detectKeyForCurrent() {
        val t = svc?.current ?: run { Toast.makeText(this, "Reproduce una canción primero", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val key = try { KeyDetector.detect(this, t.uri) } catch (_: Throwable) { null }
            runOnUiThread {
                if (key != null) { keyCache[t.uri.toString()] = key; persistCaches() }
                pushState { it.copy(currentKey = key, keyMap = keyCache.toMap()) }
                refreshNow()
            }
        }.start()
    }

    private fun lookupGenreForCurrent() {
        val t = svc?.current ?: run { Toast.makeText(this, "Reproduce una canción primero", Toast.LENGTH_SHORT).show(); return }
        pushState { it.copy(genre = "Consultando MusicBrainz…") }
        Thread {
            val result = lookupGenreOnline(t.title, t.artist)
            runOnUiThread { pushState { it.copy(genre = result ?: "Sin coincidencias") } }
        }.start()
    }

    private fun lookupGenreOnline(title: String, artist: String): String? {
        fun req(url: String): JSONObject? {
            var c: HttpURLConnection? = null
            return try {
                c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 10000; readTimeout = 10000
                    setRequestProperty("User-Agent", "AutomixAndroid/1.2")
                    setRequestProperty("Accept", "application/json")
                }
                if (c.responseCode !in 200..299) null
                else JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } catch (_: Exception) { null } finally { c?.disconnect() }
        }
        return try {
            val q = URLEncoder.encode("recording:\"${title.replace("\"", " ")}\" AND artist:\"${artist.replace("\"", " ")}\"", "UTF-8")
            val search = req("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=5") ?: return null
            val recs = search.optJSONArray("recordings") ?: return null
            for (i in 0 until recs.length()) {
                val item = recs.optJSONObject(i) ?: continue
                val id = item.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                val detail = req("https://musicbrainz.org/ws/2/recording/$id?inc=genres+tags&fmt=json") ?: continue
                val genres = linkedSetOf<String>()
                listOf("genres", "tags").forEach { name ->
                    val arr = detail.optJSONArray(name) ?: return@forEach
                    for (j in 0 until arr.length()) {
                        arr.optJSONObject(j)?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
                    }
                }
                if (genres.isNotEmpty()) return "Género: ${genres.take(5).joinToString(", ")}"
            }
            null
        } catch (_: Exception) { null }
    }

    // ---------------- Refresh de UI ----------------

    private fun refreshNow() {
        val s = svc
        val t = s?.current
        val display = buildDisplayList()
        pushState {
            it.copy(
                tracks = display,
                current = t,
                isPlaying = t != null && s?.paused == false,
                isPaused = s?.paused == true,
                currentBpm = t?.let { tr -> bpmCache[tr.uri.toString()] },
                currentKey = t?.let { tr -> keyCache[tr.uri.toString()] },
                bpmMap = bpmCache.toMap(),
                keyMap = keyCache.toMap()
            )
        }
    }

    override fun onDestroy() {
        svc?.onChange = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}

// ====================== UI ======================

@Composable
fun AutomixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2E6F40),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD8F3DC),
            onPrimaryContainer = Color(0xFF1B4332),
            background = Color(0xFFF8F9FA),
            surface = Color.White,
            onSurface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFFE9ECEF)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomixApp(
    state: MainActivity.UiSnapshot,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onStop: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onStartAutomix: () -> Unit,
    onSetSort: (String) -> Unit,
    onSetPercent: (Int) -> Unit,
    onSetFade: (Int) -> Unit,
    onSetSkip: (Int) -> Unit,
    onSetTransition: (String) -> Unit,
    onSetFolders: (Set<String>) -> Unit,
    onDetectBpm: () -> Unit,
    onDetectKey: () -> Unit,
    onLookupGenre: () -> Unit,
    onMashup: (Int) -> Unit,
    onRequestPermissions: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AUTOMIX", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.primary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(icon = { Icon(Icons.Default.Home, null) }, label = { Text("Reproductor") },
                    selected = tab == 0, onClick = { tab = 0 })
                NavigationBarItem(icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Mashup") },
                    selected = tab == 1, onClick = { tab = 1 })
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ajustes") },
                    selected = tab == 2, onClick = { tab = 2 })
            }
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad)) {
            when (tab) {
                0 -> PlayerScreen(state, onPlayPause, onNext, onPrev, onStop, onPlayTrack, onStartAutomix, onSetSort, onRequestPermissions)
                1 -> MashupScreen(state, onMashup)
                2 -> SettingsScreen(state, onSetPercent, onSetFade, onSetSkip, onSetTransition, onDetectBpm, onDetectKey, onLookupGenre)
            }
        }
    }
}

@Composable
fun PlayerScreen(
    state: MainActivity.UiSnapshot,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onStop: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onStartAutomix: () -> Unit,
    onSetSort: (String) -> Unit,
    onRequestPermissions: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            Modifier.size(90.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(state.current?.title ?: "Sin reproducción", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.current?.artist ?: "Toca una canción", fontSize = 14.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.currentBpm?.let { Chip("$it BPM") }
                                state.currentKey?.let { Chip(shortKey(it)) }
                                Chip("${state.tracks.size} pistas")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onStartAutomix) { Icon(Icons.Default.Shuffle, "Automix", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, "Anterior") }
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)) {
                            IconButton(onClick = onPlayPause, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    "Play/Pausa", tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Siguiente") }
                        IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Stop") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Aleatorio", Icons.Default.Shuffle, state.sortMode == "Aleatorio", Modifier.weight(1f)) { onSetSort("Aleatorio") }
                ModeButton("Smart", Icons.Default.Psychology, state.sortMode == "Smart", Modifier.weight(1f)) { onSetSort("Smart") }
                ModeButton("BPM", Icons.Default.Speed, state.sortMode == "BPM", Modifier.weight(1f)) { onSetSort("BPM") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("Biblioteca · ${state.tracks.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                TextButton(onClick = onRequestPermissions) { Text("Recargar", color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (state.analysisText.isNotBlank()) {
            item { Text(state.analysisText, fontSize = 12.sp, color = Color.Gray) }
        }
        if (!state.permissionGranted && state.tracks.isEmpty()) {
            item { Text("Esperando permiso de audio…", color = Color.Gray) }
        }
        items(state.tracks) { track ->
            val idx = state.tracks.indexOf(track)
            TrackItem(
                track = track,
                bpm = state.bpmMap[track.uri.toString()],
                key = state.keyMap[track.uri.toString()],
                playing = state.current?.uri == track.uri,
                onClick = { onPlayTrack(idx) }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun Chip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun shortKey(key: String) = if (key.endsWith(" minor")) key.removeSuffix(" minor") + "m" else key.removeSuffix(" major")

@Composable
fun ModeButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(shape = RoundedCornerShape(16.dp), color = bg, modifier = modifier.height(80.dp).clickable(onClick = onClick), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
        }
    }
}

@Composable
fun TrackItem(track: Track, bpm: Int?, key: String?, playing: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (playing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(bpm?.toString() ?: "—", fontSize = 12.sp, color = Color.Gray)
                Text(key?.let { shortKey(it) } ?: "—", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun MashupScreen(state: MainActivity.UiSnapshot, onMashup: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mashup", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("Elige una segunda pista para superponer con la actual.", color = Color.Gray, fontSize = 14.sp)
        if (state.tracks.size < 2) {
            Text("Necesitas al menos dos canciones en la biblioteca.", color = Color.Gray)
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.tracks) { t ->
                val idx = state.tracks.indexOf(t)
                val isCurrent = state.current?.uri == t.uri
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !isCurrent) { onMashup(idx) }
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isCurrent) Text("(actual)", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: MainActivity.UiSnapshot,
    onSetPercent: (Int) -> Unit,
    onSetFade: (Int) -> Unit,
    onSetSkip: (Int) -> Unit,
    onSetTransition: (String) -> Unit,
    onDetectBpm: () -> Unit,
    onDetectKey: () -> Unit,
    onLookupGenre: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ajustes", fontWeight = FontWeight.Bold, fontSize = 20.sp)

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Estilo de transición", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Desvanecer", "Ascender").forEach { style ->
                        FilterChip(selected = state.transition == style, onClick = { onSetTransition(style) },
                            label = { Text(style) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Reproducir ${state.percent}% de cada canción", fontWeight = FontWeight.Bold)
                Slider(value = state.percent.toFloat(), onValueChange = { onSetPercent(it.toInt()) }, valueRange = 40f..95f)
                Text("Transición: ${state.fadeSec} segundos", fontWeight = FontWeight.Bold)
                Slider(value = state.fadeSec.toFloat(), onValueChange = { onSetFade(it.toInt()) }, valueRange = 2f..15f)
                Text(if (state.skipSec == 0) "No saltar intro" else "Saltar los primeros ${state.skipSec} s", fontWeight = FontWeight.Bold)
                Slider(value = state.skipSec.toFloat(), onValueChange = { onSetSkip(it.toInt()) }, valueRange = 0f..45f)
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Análisis de la pista actual", fontWeight = FontWeight.Bold)
                Button(onClick = onDetectBpm, modifier = Modifier.fillMaxWidth()) {
                    Text(state.currentBpm?.let { "BPM: $it" } ?: "Detectar BPM")
                }
                Button(onClick = onDetectKey, modifier = Modifier.fillMaxWidth()) {
                    Text(state.currentKey?.let { "Tonalidad: ${shortKey(it)}" } ?: "Detectar tonalidad")
                }
                Button(onClick = onLookupGenre, modifier = Modifier.fillMaxWidth()) {
                    Text("Buscar género online")
                }
                state.genre?.let { Text(it, fontSize = 13.sp, color = Color.Gray) }
                if (state.analysisText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(state.analysisText, fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
}
