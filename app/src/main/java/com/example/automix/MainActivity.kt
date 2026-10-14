package com.example.automix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore.Audio.Media
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val SORT_RANDOM = "Aleatorio"
        private const val SORT_SMART = "Smart"
        private const val SORT_TITLE = "Título"
        private const val SORT_ARTIST = "Artista"
        private const val SORT_BPM = "BPM"
    }

    private var svc: AutomixService? = null
    private var allTracks: List<Track> = emptyList()
    private var tracks: List<Track> = emptyList()
    private var skip = 0
    private var folders: Set<String> = emptySet()
    private var percent = 70
    private var fade = 8

    private lateinit var prefs: SharedPreferences
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var pauseBtn: Button
    private lateinit var automixBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var previousBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var list: ListView
    private lateinit var folderBtn: Button
    private lateinit var bpmBtn: Button
    private lateinit var bpmStatus: TextView
    private lateinit var keyBtn: Button
    private lateinit var keyStatus: TextView
    private lateinit var analysisStatus: TextView
    private lateinit var genreBtn: Button
    private lateinit var genreStatus: TextView
    private lateinit var randomModeBtn: Button
    private lateinit var smartModeBtn: Button
    private lateinit var mashupBtn: Button
    private lateinit var transitionBtn: Button
    private var transitionStyle = "Desvanecer"
    private lateinit var sortBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var settingsScroll: ScrollView
    private lateinit var libraryHeader: TextView
    private lateinit var trackAdapter: TrackAdapter
    private var displayTracks: List<Track> = emptyList()
    private var randomRank: Map<String, Int> = emptyMap()
    private var sortMode = SORT_RANDOM
    private var lastManualSort = SORT_TITLE
    private val collator: java.text.Collator = java.text.Collator.getInstance()
    private val bpmCache = mutableMapOf<String, Int>()
    private val keyCache = mutableMapOf<String, String>()
    @Volatile private var libraryAnalysisRunning = false

    private val audioPerm: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as AutomixService.LocalBinder).service
            s.playPercent = percent
            s.fadeSec = fade
            s.skipIntroSec = skip
            s.onChange = { refreshNow() }
            svc = s
            if (displayTracks.isNotEmpty()) s.reorderQueue(displayTracks)
            refreshNow()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("automix", MODE_PRIVATE)
        percent = prefs.getInt("percent", 70)
        fade = prefs.getInt("fade", 8)
        skip = prefs.getInt("skip", 0)
        sortMode = prefs.getString("sort_mode", SORT_RANDOM) ?: SORT_RANDOM
        if (sortMode !in listOf(SORT_RANDOM, SORT_SMART, SORT_TITLE, SORT_ARTIST, SORT_BPM)) sortMode = SORT_RANDOM
        lastManualSort = prefs.getString("sort_manual", SORT_TITLE) ?: SORT_TITLE
        if (lastManualSort !in listOf(SORT_TITLE, SORT_ARTIST, SORT_BPM)) lastManualSort = SORT_TITLE
        folders = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
        try {
            val savedBpm = JSONObject(prefs.getString("bpm_cache", "{}") ?: "{}")
            savedBpm.keys().forEach { k -> bpmCache[k] = savedBpm.optInt(k) }
            val savedKeys = JSONObject(prefs.getString("key_cache", "{}") ?: "{}")
            savedKeys.keys().forEach { k -> savedKeys.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { keyCache[k] = it } }
        } catch (_: Exception) {}
        buildUi()
        bindService(Intent(this, AutomixService::class.java), conn, BIND_AUTO_CREATE)
        ensurePermissions()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val activeGreen = Color.rgb(46, 125, 50)
    private val neutralTint = Color.rgb(235, 238, 235)
    private val activeText = Color.WHITE
    private val neutralText = Color.rgb(35, 45, 35)

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        backgroundTintList = ColorStateList.valueOf(neutralTint)
        setTextColor(neutralText)
        setOnClickListener { onClick() }
    }

    private fun setActive(button: Button, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (active) activeGreen else neutralTint)
        button.setTextColor(if (active) activeText else neutralText)
    }

    private fun addSlider(
        root: LinearLayout, lo: Int, hi: Int, value: Int,
        label: (Int) -> String, onValue: (Int) -> Unit
    ) {
        val tv = TextView(this).apply { text = label(value) }
        val sb = SeekBar(this).apply {
            max = hi - lo
            progress = value - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    tv.text = label(v)
                    onValue(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(tv)
        root.addView(sb)
    }

    private fun addRow(root: LinearLayout, vararg views: Button) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach {
            row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(6))
            setBackgroundColor(Color.rgb(248, 250, 248))
        }
        nowTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            text = "Automix"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowSub = TextView(this).apply {
            textSize = 14f
            text = "Toca una canción o pulsa Automix"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(nowTitle)
        root.addView(nowSub)

        // Fila 1: reproducción
        automixBtn = btn("Automix") { startAutomix(0) }
        previousBtn = btn("⏮") { svc?.previous() }
        pauseBtn = btn("Pausa") { svc?.togglePause() }
        nextBtn = btn("⏭") { svc?.next() }
        stopBtn = btn("Stop") { svc?.stopAll(); refreshNow() }
        addRow(root, automixBtn, previousBtn, pauseBtn, nextBtn, stopBtn)

        // Fila 2: modos de orden (la lista visible y la cola de reproducción siempre coinciden)
        randomModeBtn = btn("Aleatorio") { selectSort(SORT_RANDOM) }
        smartModeBtn = btn("Smart") { selectSort(SORT_SMART) }
        sortBtn = btn("Ordenar ▾") { showSortDialog() }
        addRow(root, randomModeBtn, smartModeBtn, sortBtn)

        // Fila 3: carpetas, ajustes y mashup
        folderBtn = btn("Carpetas: todas") { showFolderDialog() }
        settingsBtn = btn("Ajustes") { toggleSettings() }
        mashupBtn = btn("Mashup") { showMashupDialog() }
        addRow(root, folderBtn, settingsBtn, mashupBtn)

        // Panel de ajustes y análisis (oculto por defecto, con su propio desplazamiento)
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        transitionBtn = btn("Transición: Desvanecer") { showTransitionDialog() }
        settings.addView(transitionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bpmBtn = btn("Detectar BPM actual") { detectCurrentBpm() }
        settings.addView(bpmBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bpmStatus = TextView(this).apply {
            text = "BPM: sin analizar"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        settings.addView(bpmStatus)

        keyBtn = btn("Detectar tonalidad actual") { detectCurrentKey() }
        settings.addView(keyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        keyStatus = TextView(this).apply {
            text = "Tonalidad: sin analizar"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        settings.addView(keyStatus)

        genreBtn = btn("Buscar género online") { searchCurrentGenre() }
        settings.addView(genreBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        genreStatus = TextView(this).apply {
            text = "Género: sin consultar"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        settings.addView(genreStatus)

        addSlider(settings, 40, 95, percent, { "Reproducir $it% de cada canción" }) {
            percent = it
            svc?.playPercent = it
            prefs.edit().putInt("percent", it).apply()
        }
        addSlider(settings, 2, 15, fade, { "Transición: $it segundos" }) {
            fade = it
            svc?.fadeSec = it
            prefs.edit().putInt("fade", it).apply()
        }
        addSlider(settings, 0, 45, skip, { if (it == 0) "No saltar intro" else "Saltar los primeros $it s" }) {
            skip = it
            svc?.skipIntroSec = it
            prefs.edit().putInt("skip", it).apply()
        }

        settingsScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(settings)
        }
        root.addView(settingsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))

        // Biblioteca compacta: ocupa el espacio restante y se desplaza sola
        libraryHeader = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            text = "Biblioteca"
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(libraryHeader)
        analysisStatus = TextView(this).apply {
            text = "Biblioteca: esperando permiso para analizar BPM y tonalidad"
            textSize = 12f
            setPadding(0, dp(2), 0, dp(4))
        }
        root.addView(analysisStatus)

        trackAdapter = TrackAdapter()
        list = ListView(this)
        list.adapter = trackAdapter
        list.setOnItemClickListener { _, _, position, _ -> startAutomix(position) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        updateModeButtons()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) needed += audioPerm
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) loadTracks() else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1) {
            if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) {
                // Wait until the permission dialog has fully closed before querying MediaStore.
                window.decorView.post { safelyLoadTracks() }
            } else {
                nowSub.text = "Permiso de música no concedido"
                analysisStatus.text = "Concede acceso a música para cargar la biblioteca."
            }
        }
    }

    private fun safelyLoadTracks() {
        try {
            loadTracks()
        } catch (t: Throwable) {
            libraryAnalysisRunning = false
            nowSub.text = "No se pudo cargar la biblioteca"
            analysisStatus.text = "Error al leer archivos. Cierra y vuelve a abrir Automix."
            Toast.makeText(this, "Error leyendo la biblioteca: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            android.util.Log.e("Automix", "MediaStore library scan failed", t)
        }
    }

    private fun loadTracks() {
        if (checkSelfPermission(audioPerm) != PackageManager.PERMISSION_GRANTED) {
            nowSub.text = "Necesito permiso para leer tu música"
            return
        }
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
            require(iId >= 0 && iTitle >= 0 && iDur >= 0) { "MediaStore missing required columns" }
            while (c.moveToNext()) {
                val path = if (iData >= 0) c.getString(iData) else null
                found += Track(
                    ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, c.getLong(iId)),
                    c.getString(iTitle) ?: "Sin título",
                    if (iArtist >= 0) c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: "Artista desconocido" else "Artista desconocido",
                    c.getLong(iDur),
                    path?.let { File(it).parent } ?: "Música"
                )
            }
        }
        allTracks = found
        newRandomOrder()
        applyFilter()
        if (found.isEmpty()) {
            nowSub.text = "No encontré canciones en el dispositivo"
            analysisStatus.text = "No hay canciones compatibles en la biblioteca."
        } else {
            val missing = found.count { !bpmCache.containsKey(it.uri.toString()) || !keyCache.containsKey(it.uri.toString()) }
            analysisStatus.text = "Biblioteca: ${found.size} canciones · ${found.size - missing} analizadas · ${bpmCache.size} BPM · ${keyCache.size} tonalidades"
            if (missing > 0) analyzeLibraryInBackground()
        }
    }

    private fun applyFilter() {
        val valid = folders.filter { f -> allTracks.any { it.folder == f } }.toSet()
        tracks = if (valid.isEmpty()) allTracks else allTracks.filter { it.folder in valid }
        folderBtn.text = if (valid.isEmpty()) "Carpetas: todas" else "Carpetas (${valid.size})"
        setActive(folderBtn, valid.isNotEmpty())
        rebuildDisplay()
    }

    private fun showFolderDialog() {
        val counts = allTracks.groupingBy { it.folder }.eachCount()
        val paths = counts.keys.sortedBy { it.lowercase() }
        if (paths.isEmpty()) {
            Toast.makeText(this, "No hay carpetas con música", Toast.LENGTH_SHORT).show()
            return
        }
        val names = paths.map { p -> p.substringAfterLast('/').ifEmpty { "/" } + " (" + counts[p] + ")" }.toTypedArray()
        val sel = folders.toMutableSet()
        val checked = BooleanArray(paths.size) { paths[it] in sel }
        AlertDialog.Builder(this)
            .setTitle("Carpetas a mezclar")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val p = paths[which]
                if (isChecked) sel.add(p) else sel.remove(p)
            }
            .setPositiveButton("Aplicar") { _, _ ->
                folders = sel
                prefs.edit().putStringSet("folders", sel).apply()
                applyFilter()
                Toast.makeText(this, "Lista actualizada", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Todas") { _, _ ->
                folders = emptySet()
                prefs.edit().remove("folders").apply()
                applyFilter()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startAutomix(startAt: Int) {
        if (displayTracks.isEmpty()) {
            Toast.makeText(this, "No hay canciones cargadas", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, AutomixService::class.java))
        svc?.let {
            it.playPercent = percent
            it.fadeSec = fade
            it.skipIntroSec = skip
            it.transitionStyle = transitionStyle
            // La cola de reproducción sigue exactamente el orden de la lista visible.
            it.startOrdered(displayTracks, startAt.coerceAtLeast(0))
        }
    }

    private class SmartItem(val track: Track, val bpm: Int?, val key: String?)

    private fun smartReorder(input: List<Track>): List<Track> {
        if (input.size < 3) return input
        val items = input.map { t ->
            val id = t.uri.toString()
            SmartItem(t, bpmCache[id], keyCache[id])
        }
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
                if (cost < bestCost) {
                    bestCost = cost
                    bestIdx = i
                }
            }
            last = known.removeAt(bestIdx)
            ordered.add(last.track)
        }
        for (u in unknown) ordered.add(u.track)
        return ordered
    }

    private fun transitionCost(a: SmartItem, b: SmartItem): Double {
        val x = a.bpm
        val y = b.bpm
        val bpmCost = if (x != null && y != null) {
            minOf(kotlin.math.abs(x - y), kotlin.math.abs(x - y * 2), kotlin.math.abs(x * 2 - y)).toDouble() / 12.0
        } else 2.0
        val ka = a.key
        val kb = b.key
        val keyCost = if (ka != null && kb != null) {
            if (harmonicallyCompatible(ka, kb)) 0.0 else 3.0
        } else 1.2
        return bpmCost + keyCost
    }

    private val camelotMap: Map<String, String> = mapOf(
        "C major" to "8B", "G major" to "9B", "D major" to "10B", "A major" to "11B", "E major" to "12B", "B major" to "1B",
        "F# major" to "2B", "C# major" to "3B", "G# major" to "4B", "D# major" to "5B", "A# major" to "6B", "F major" to "7B",
        "A minor" to "8A", "E minor" to "9A", "B minor" to "10A", "F# minor" to "11A", "C# minor" to "12A", "G# minor" to "1A",
        "D# minor" to "2A", "A# minor" to "3A", "F minor" to "4A", "C minor" to "5A", "G minor" to "6A", "D minor" to "7A"
    )

    /** Rueda Camelot: misma posición (relativo mayor/menor) o posición contigua con la misma letra. */
    private fun harmonicallyCompatible(a: String, b: String): Boolean {
        val x = camelotMap[a] ?: return false
        val y = camelotMap[b] ?: return false
        val xn = x.dropLast(1).toInt()
        val yn = y.dropLast(1).toInt()
        if (xn == yn) return true
        val sameMode = x.last() == y.last()
        val neighbor = (xn % 12) + 1 == yn || (yn % 12) + 1 == xn
        return sameMode && neighbor
    }

    private fun persistAnalysisCaches() {
        val bpmJson = JSONObject()
        bpmCache.forEach { (k, v) -> bpmJson.put(k, v) }
        val keyJson = JSONObject()
        keyCache.forEach { (k, v) -> keyJson.put(k, v) }
        prefs.edit().putString("bpm_cache", bpmJson.toString()).putString("key_cache", keyJson.toString()).apply()
    }

    private fun analyzeLibraryInBackground() {
        if (libraryAnalysisRunning) return
        libraryAnalysisRunning = true
        Thread {
            val candidates = allTracks.toList().filter { !bpmCache.containsKey(it.uri.toString()) || !keyCache.containsKey(it.uri.toString()) }
            var completed = 0
            for (track in candidates) {
                if (isFinishing || isDestroyed) break
                val id = track.uri.toString()
                try {
                    if (!bpmCache.containsKey(id)) BpmDetector.detect(this, track.uri)?.let { bpmCache[id] = it }
                    if (!keyCache.containsKey(id)) KeyDetector.detect(this, track.uri)?.let { keyCache[id] = it }
                    persistAnalysisCaches()
                } catch (t: Throwable) {
                    android.util.Log.e("Automix", "Analysis failed for $id", t)
                }
                completed++
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        analysisStatus.text = "Analizando biblioteca: $completed/${candidates.size} · BPM ${bpmCache.size} · tonalidades ${keyCache.size}"
                        if (svc?.current?.uri == track.uri) {
                            bpmStatus.text = bpmCache[id]?.let { "BPM estimado: $it" } ?: "BPM: no detectado"
                            keyStatus.text = keyCache[id]?.let { "Tonalidad estimada: $it" } ?: "Tonalidad: no detectada"
                            refreshNow()
                        }
                        trackAdapter.notifyDataSetChanged()
                    }
                }
            }
            libraryAnalysisRunning = false
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    analysisStatus.text = "Análisis terminado · BPM ${bpmCache.size} · tonalidades ${keyCache.size}. Los resultados quedan guardados."
                    // Con orden por BPM o Smart, la lista y la cola se actualizan con los datos nuevos.
                    if (sortMode == SORT_BPM || sortMode == SORT_SMART) rebuildDisplay() else trackAdapter.notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun detectCurrentKey() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        keyBtn.isEnabled = false
        keyBtn.text = "Analizando tonalidad…"
        keyStatus.text = "Tonalidad: analizando audio local…"
        Thread {
            val key = try { KeyDetector.detect(this, track.uri) } catch (t: Throwable) {
                android.util.Log.e("Automix", "Key analysis failed", t); null
            }
            runOnUiThread {
                keyBtn.isEnabled = true
                keyBtn.text = "Detectar tonalidad actual"
                if (key != null) { keyCache[track.uri.toString()] = key; persistAnalysisCaches() }
                keyStatus.text = if (key != null) "Tonalidad estimada: $key (guardada)" else "No pude estimar la tonalidad de esta canción"
                setActive(keyBtn, key != null)
                trackAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun showTransitionDialog() {
        val options = arrayOf("Desvanecer", "Ascender")
        val selected = options.indexOf(transitionStyle).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Estilo de transición")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                transitionStyle = options[which]
                transitionBtn.text = "Transición: $transitionStyle"
                svc?.transitionStyle = transitionStyle
                setActive(transitionBtn, true)
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showMashupDialog() {
        if (displayTracks.size < 2) {
            Toast.makeText(this, "Necesitas al menos dos canciones", Toast.LENGTH_SHORT).show()
            return
        }
        val names = displayTracks.map { "${it.title} — ${it.artist}" }.toTypedArray()
        val currentIndex = displayTracks.indexOfFirst { it.uri == svc?.current?.uri }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Mashup: elige la segunda canción")
            .setSingleChoiceItems(names, -1) { dialog, which ->
                if (which == currentIndex) {
                    Toast.makeText(this, "Elige una canción distinta a la principal", Toast.LENGTH_SHORT).show()
                    return@setSingleChoiceItems
                }
                startForegroundService(Intent(this, AutomixService::class.java))
                svc?.let {
                    it.playPercent = percent
                    it.fadeSec = fade
                    it.skipIntroSec = skip
                    it.transitionStyle = transitionStyle
                    it.startMashup(displayTracks, currentIndex, which)
                }
                dialog.dismiss()
                Toast.makeText(this, "Mashup iniciado: superposición aproximada de 30 segundos", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun detectCurrentBpm() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        bpmBtn.isEnabled = false
        bpmBtn.text = "Analizando audio…"
        bpmStatus.text = "BPM: analizando los primeros 90 segundos…"
        Thread {
            val bpm = try { BpmDetector.detect(this, track.uri) } catch (t: Throwable) {
                android.util.Log.e("Automix", "BPM analysis failed", t); null
            }
            runOnUiThread {
                bpmBtn.isEnabled = true
                bpmBtn.text = "Detectar BPM actual"
                if (bpm != null) { bpmCache[track.uri.toString()] = bpm; persistAnalysisCaches() }
                bpmStatus.text = if (bpm != null) "BPM estimado: $bpm (guardado para Smart Reorder)" else "No pude estimar el BPM de esta canción"
                setActive(bpmBtn, bpm != null)
                trackAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun searchCurrentGenre() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        genreBtn.isEnabled = false
        genreBtn.text = "Buscando género…"
        genreStatus.text = "Consultando MusicBrainz por Internet…"
        Thread {
            val result = lookupGenreOnline(track.title, track.artist)
            runOnUiThread {
                genreBtn.isEnabled = true
                genreBtn.text = "Buscar género online"
                genreStatus.text = result ?: "No encontré un género fiable. Prueba con el título y artista correctos."
                setActive(genreBtn, result != null)
            }
        }.start()
    }

    /** Looks up public MusicBrainz metadata. Genre labels are community tags and may be absent. */
    private fun lookupGenreOnline(title: String, artist: String): String? {
        fun requestJson(urlText: String): JSONObject? {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "AutomixAndroid/1.1 (local music genre lookup)")
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode !in 200..299) null
                else JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
        return try {
            val qTitle = title.replace("\"", " ").trim()
            val qArtist = artist.replace("\"", " ").trim()
            val query = "recording:\"$qTitle\" AND artist:\"$qArtist\""
            val encoded = URLEncoder.encode(query, "UTF-8")
            val search = requestJson("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=5")
                ?: return "Sin conexión o MusicBrainz no respondió."
            val recordings = search.optJSONArray("recordings") ?: return null
            var bestId: String? = null
            var bestTitle: String? = null
            for (i in 0 until recordings.length()) {
                val item = recordings.optJSONObject(i) ?: continue
                val candidateTitle = item.optString("title", "")
                val credits = item.optJSONArray("artist-credit")
                val candidateArtist = if (credits != null && credits.length() > 0) {
                    credits.optJSONObject(0)?.optJSONObject("artist")?.optString("name", "") ?: ""
                } else ""
                if (candidateTitle.equals(title, ignoreCase = true) &&
                    (artist.equals("Artista desconocido", true) || candidateArtist.contains(artist, true) || artist.contains(candidateArtist, true))) {
                    bestId = item.optString("id", "").takeIf { it.isNotBlank() }
                    bestTitle = candidateTitle
                    break
                }
                if (bestId == null && candidateTitle.equals(title, ignoreCase = true)) {
                    bestId = item.optString("id", "").takeIf { it.isNotBlank() }
                    bestTitle = candidateTitle
                }
            }
            val id = bestId ?: return lookupItunesGenre(title, artist)
                ?: "No encontré coincidencias para «$title» — $artist."
            val detail = requestJson("https://musicbrainz.org/ws/2/recording/$id?inc=genres+tags+artist-credits&fmt=json")
            val genres = linkedSetOf<String>()
            fun collect(arrayName: String) {
                val arr = detail?.optJSONArray(arrayName) ?: return
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    if (name.isNotBlank()) genres.add(name)
                }
            }
            collect("genres")
            collect("tags")
            if (genres.isEmpty()) {
                lookupItunesGenre(title, artist)
                    ?: "Encontré «${bestTitle ?: title}», pero no encontré etiquetas de género fiables en MusicBrainz ni iTunes."
            } else {
                "Género/etiquetas: ${genres.take(5).joinToString(", ")}\nFuente: MusicBrainz (etiquetas comunitarias; pueden ser imprecisas)."
            }
        } catch (_: Exception) {
            "No pude consultar el género. Comprueba tu conexión a Internet."
        }
    }


    /** Fallback de búsqueda: el catálogo de iTunes devuelve el género principal cuando está disponible. */
    private fun lookupItunesGenre(title: String, artist: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val term = URLEncoder.encode("$title $artist", "UTF-8")
            connection = (URL("https://itunes.apple.com/search?term=$term&entity=song&limit=8").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "AutomixAndroid/1.2")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val results = json.optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val trackName = item.optString("trackName", "")
                val artistName = item.optString("artistName", "")
                val genre = item.optString("primaryGenreName", "")
                if (genre.isNotBlank() && trackName.equals(title, true) &&
                    (artist.equals("Artista desconocido", true) || artistName.contains(artist, true) || artist.contains(artistName, true))) {
                    return "Género probable: $genre\nCoincidencia: $trackName — $artistName\nFuente: catálogo de iTunes."
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ---------- Orden de la biblioteca (lista visible = cola de reproducción) ----------

    private fun newRandomOrder() {
        randomRank = allTracks.shuffled().mapIndexed { i, t -> t.uri.toString() to i }.toMap()
    }

    private fun selectSort(mode: String) {
        sortMode = mode
        if (mode == SORT_RANDOM) newRandomOrder()
        if (mode == SORT_TITLE || mode == SORT_ARTIST || mode == SORT_BPM) lastManualSort = mode
        prefs.edit().putString("sort_mode", sortMode).putString("sort_manual", lastManualSort).apply()
        updateModeButtons()
        rebuildDisplay()
        val cur = svc?.current?.uri
        val idx = if (cur != null) displayTracks.indexOfFirst { it.uri == cur } else -1
        list.setSelection(if (idx >= 0) idx else 0)
        if (mode == SORT_SMART) {
            val known = displayTracks.count { bpmCache.containsKey(it.uri.toString()) && keyCache.containsKey(it.uri.toString()) }
            Toast.makeText(this, "Smart: $known de ${displayTracks.size} canciones tienen BPM y tonalidad analizados", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Orden: $mode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSortDialog() {
        val options = arrayOf(SORT_TITLE, SORT_ARTIST, SORT_BPM)
        AlertDialog.Builder(this)
            .setTitle("Ordenar biblioteca")
            .setSingleChoiceItems(options, options.indexOf(sortMode)) { dialog, which ->
                dialog.dismiss()
                selectSort(options[which])
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateModeButtons() {
        val manual = sortMode == SORT_TITLE || sortMode == SORT_ARTIST || sortMode == SORT_BPM
        setActive(randomModeBtn, sortMode == SORT_RANDOM)
        setActive(smartModeBtn, sortMode == SORT_SMART)
        setActive(sortBtn, manual)
        sortBtn.text = if (manual) "Orden: $sortMode" else "Ordenar ▾"
    }

    private fun toggleSettings() {
        val show = settingsScroll.visibility != View.VISIBLE
        settingsScroll.visibility = if (show) View.VISIBLE else View.GONE
        setActive(settingsBtn, show)
    }

    /** Recalcula la lista visible según el modo y sincroniza la cola del servicio sin cortar la canción actual. */
    private fun rebuildDisplay() {
        val base = tracks
        val unknownArtist = "Artista desconocido"
        displayTracks = when (sortMode) {
            SORT_RANDOM -> base.sortedBy { randomRank[it.uri.toString()] ?: Int.MAX_VALUE }
            SORT_SMART -> smartReorder(base.sortedWith(Comparator<Track> { a, b -> collator.compare(a.title, b.title) }))
            SORT_ARTIST -> base.sortedWith(Comparator<Track> { a, b ->
                val ua = a.artist == unknownArtist
                val ub = b.artist == unknownArtist
                if (ua != ub) {
                    if (ua) 1 else -1
                } else {
                    val c = collator.compare(a.artist, b.artist)
                    if (c != 0) c else collator.compare(a.title, b.title)
                }
            })
            SORT_BPM -> base.sortedWith(Comparator<Track> { a, b ->
                val x = bpmCache[a.uri.toString()] ?: Int.MAX_VALUE
                val y = bpmCache[b.uri.toString()] ?: Int.MAX_VALUE
                if (x != y) x.compareTo(y) else collator.compare(a.title, b.title)
            })
            else -> base.sortedWith(Comparator<Track> { a, b -> collator.compare(a.title, b.title) })
        }
        trackAdapter.notifyDataSetChanged()
        libraryHeader.text = "Biblioteca · ${displayTracks.size} canciones · orden: $sortMode"
        svc?.reorderQueue(displayTracks)
    }

    private fun shortKey(key: String): String =
        if (key.endsWith(" minor")) key.removeSuffix(" minor") + "m" else key.removeSuffix(" major")

    private class RowViews(val title: TextView, val artist: TextView, val meta: TextView)

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount(): Int = displayTracks.size
        override fun getItem(position: Int): Any = displayTracks[position]
        override fun getItemId(position: Int): Long = position.toLong()

        private fun buildRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val textCol = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(this@MainActivity).apply {
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val artist = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Color.rgb(100, 110, 100))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(title)
            textCol.addView(artist)
            val meta = TextView(this@MainActivity).apply {
                textSize = 12f
                gravity = Gravity.END
                setTextColor(Color.rgb(60, 80, 60))
            }
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.tag = RowViews(title, artist, meta)
            return row
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val views = row.tag as RowViews
            val t = displayTracks[position]
            val id = t.uri.toString()
            val playing = svc?.current?.uri == t.uri
            views.title.text = t.title
            views.title.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            views.title.setTextColor(if (playing) activeGreen else Color.BLACK)
            views.artist.text = t.artist
            val bpm = bpmCache[id]?.toString() ?: "—"
            val key = keyCache[id]
            views.meta.text = if (key == null) bpm else bpm + "\n" + shortKey(key)
            row.setBackgroundColor(if (playing) Color.rgb(232, 245, 233) else Color.TRANSPARENT)
            return row
        }
    }

    private fun refreshNow() {
        val s = svc
        val t = s?.current
        nowTitle.text = t?.title ?: "Automix"
        if (t != null) {
            val id = t.uri.toString()
            bpmStatus.text = bpmCache[id]?.let { "BPM estimado: $it (guardado)" } ?: "BPM: pendiente de análisis"
            keyStatus.text = keyCache[id]?.let { "Tonalidad estimada: $it (guardada)" } ?: "Tonalidad: pendiente de análisis"
            val parts = mutableListOf(t.artist)
            bpmCache[id]?.let { parts.add("$it BPM") }
            keyCache[id]?.let { parts.add(shortKey(it)) }
            nowSub.text = parts.joinToString(" · ")
        } else {
            nowSub.text = "Toca una canción o pulsa Automix"
        }
        pauseBtn.text = if (s?.paused == true) "Reanudar" else "Pausa"
        val playing = t != null
        setActive(automixBtn, playing && s?.paused == false)
        setActive(pauseBtn, s?.paused == true)
        setActive(stopBtn, false)
        setActive(previousBtn, false)
        setActive(nextBtn, false)
        trackAdapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        svc?.onChange = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}
