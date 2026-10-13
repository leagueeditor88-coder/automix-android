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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {

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
    private lateinit var transitionBtn: Button
    private lateinit var genreSummary: TextView
    private lateinit var nextSongStatus: TextView
    private lateinit var mashupPrimaryBtn: Button
    private lateinit var mashupSecondaryBtn: Button
    private lateinit var mashupStatus: TextView
    private lateinit var mashupStartBtn: Button
    private lateinit var randomNavBtn: Button
    private lateinit var mashupNavBtn: Button
    private lateinit var settingsNavBtn: Button
    private lateinit var playerPage: LinearLayout
    private lateinit var mashupPage: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var rootHost: FrameLayout
    private var transitionStyle = "Desvanecer"
    private var smartMode = false
    private var currentPage = "player"
    private var selectedMashupA: Track? = null
    private var selectedMashupB: Track? = null
    private var mashupDurationSec = 30
    private var bpmAnalyzing = false
    private var keyAnalyzing = false
    private var genreLookupRunning = false
    private val bpmCache = ConcurrentHashMap<String, Int>()
    private val keyCache = ConcurrentHashMap<String, String>()
    private val genreCache = ConcurrentHashMap<String, String>()
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
            s.transitionStyle = transitionStyle
            s.onChange = { refreshNow() }
            svc = s
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
        smartMode = prefs.getBoolean("smart_mode", false)
        transitionStyle = prefs.getString("transition_style", "Desvanecer") ?: "Desvanecer"
        mashupDurationSec = prefs.getInt("mashup_duration", 30).coerceIn(10, 90)
        folders = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
        try {
            val savedBpm = JSONObject(prefs.getString("bpm_cache", "{}") ?: "{}")
            savedBpm.keys().forEach { k -> bpmCache[k] = savedBpm.optInt(k) }
            val savedKeys = JSONObject(prefs.getString("key_cache", "{}") ?: "{}")
            savedKeys.keys().forEach { k -> savedKeys.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { keyCache[k] = it } }
            val savedGenres = JSONObject(prefs.getString("genre_cache", "{}") ?: "{}")
            savedGenres.keys().forEach { k -> savedGenres.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { genreCache[k] = it } }
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
            setPadding(dp(14), dp(14), dp(14), dp(8))
            setBackgroundColor(Color.rgb(248, 250, 248))
        }
        val brand = TextView(this).apply {
            text = "AUTOMIX"
            textSize = 12f
            letterSpacing = 0.18f
            setTextColor(Color.rgb(46, 125, 50))
            setTypeface(null, Typeface.BOLD)
        }
        nowTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            text = "Elige una canción"
            setTextColor(Color.rgb(25, 35, 27))
            setPadding(0, dp(2), 0, 0)
        }
        nowSub = TextView(this).apply {
            textSize = 13f
            text = "Reproductor local · transición automática"
            setTextColor(Color.rgb(88, 98, 88))
        }
        genreSummary = TextView(this).apply {
            textSize = 12f
            text = "Etiquetas: sin consultar"
            setTextColor(Color.rgb(46, 125, 50))
            setPadding(0, dp(3), 0, dp(4))
        }
        root.addView(brand)
        root.addView(nowTitle)
        root.addView(nowSub)
        root.addView(genreSummary)

        rootHost = FrameLayout(this)
        root.addView(rootHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        buildPlayerPage()
        buildMashupPage()
        buildSettingsPage()
        rootHost.addView(playerPage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        rootHost.addView(mashupPage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        rootHost.addView(settingsPage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        randomNavBtn = btn("Reproductor") { showPage("player") }
        mashupNavBtn = btn("Mashup") { showPage("mashup") }
        settingsNavBtn = btn("Ajustes") { showPage("settings") }
        listOf(randomNavBtn, mashupNavBtn, settingsNavBtn).forEach { b ->
            nav.addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        root.addView(nav)
        setContentView(root)
        showPage("player")
        updateMashupLabels()
        updateModeButtons()
        transitionBtn.text = "Transición: $transitionStyle"
    }

    private fun pageColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(8), dp(2), dp(4))
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.rgb(31, 48, 34))
        setPadding(dp(2), dp(4), dp(2), dp(7))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(80, 91, 81))
        setPadding(dp(2), 0, dp(2), dp(8))
    }

    private fun wrapScroll(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content, ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun buildPlayerPage() {
        playerPage = pageColumn()
        playerPage.addView(heading("Reproductor"))
        playerPage.addView(body("Elige una canción o deja que Automix organice la biblioteca."))
        automixBtn = btn("▶ Automix") { startAutomix(-1) }
        folderBtn = btn("Carpetas: todas") { showFolderDialog() }
        stopBtn = btn("■ Detener") { svc?.stopAll(); refreshNow() }
        addRow(playerPage, automixBtn, folderBtn, stopBtn)
        previousBtn = btn("⏮ Anterior") { svc?.previous() }
        pauseBtn = btn("Ⅱ Pausa") { svc?.togglePause() }
        nextBtn = btn("Siguiente ⏭") { svc?.next() }
        addRow(playerPage, previousBtn, pauseBtn, nextBtn)
        randomModeBtn = btn("Aleatorio") {
            smartMode = false
            prefs.edit().putBoolean("smart_mode", false).apply()
            updateModeButtons()
            Toast.makeText(this, "Modo aleatorio seleccionado", Toast.LENGTH_SHORT).show()
        }
        smartModeBtn = btn("Smart Reorder") {
            smartMode = !smartMode
            prefs.edit().putBoolean("smart_mode", smartMode).apply()
            updateModeButtons()
            Toast.makeText(this, if (smartMode) "Smart Reorder activado: BPM y tonalidad cuando estén disponibles" else "Smart Reorder desactivado", Toast.LENGTH_LONG).show()
        }
        addRow(playerPage, randomModeBtn, smartModeBtn)
        nextSongStatus = body("Siguiente: Automix elegirá según el modo seleccionado.")
        playerPage.addView(nextSongStatus)
        list = ListView(this)
        list.dividerHeight = dp(1)
        list.setOnItemClickListener { _, _, position, _ -> startAutomix(position) }
        playerPage.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    private fun buildMashupPage() {
        val content = pageColumn()
        content.addView(heading("Mashup"))
        content.addView(body("Selecciona dos pistas o busca una segunda canción compatible. La mezcla superpone audio y hace un fundido; todavía no sincroniza automáticamente los golpes."))
        mashupPrimaryBtn = btn("Pista A · elegir canción") { showMashupTrackPicker(true) }
        mashupSecondaryBtn = btn("Pista B · elegir canción") { showMashupTrackPicker(false) }
        content.addView(mashupPrimaryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(mashupSecondaryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val findCompatible = btn("Buscar segunda pista compatible") { findCompatibleMashupTrack() }
        content.addView(findCompatible, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        mashupStatus = body("Elige la pista A y la pista B.")
        content.addView(mashupStatus)
        addSlider(content, 10, 90, mashupDurationSec, { "Superposición: $it segundos" }) {
            mashupDurationSec = it
            prefs.edit().putInt("mashup_duration", it).apply()
        }
        mashupStartBtn = btn("▶ Iniciar Mashup") { startSelectedMashup() }
        content.addView(mashupStartBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(body("Importante: la compatibilidad se estima con BPM/tonalidad guardados. Sin esos datos, la selección automática es aproximada."))
        mashupPage = wrapScroll(content)
    }

    private fun buildSettingsPage() {
        val content = pageColumn()
        content.addView(heading("Ajustes y análisis"))
        content.addView(body("Los ajustes se guardan en el teléfono y se conservan al cambiar de pantalla."))
        transitionBtn = btn("Transición: $transitionStyle") { showTransitionDialog() }
        content.addView(transitionBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addSlider(content, 40, 95, percent, { "Reproducir $it% de cada canción" }) {
            percent = it
            svc?.playPercent = it
            prefs.edit().putInt("percent", it).apply()
        }
        addSlider(content, 2, 15, fade, { "Transición: $it segundos" }) {
            fade = it
            svc?.fadeSec = it
            prefs.edit().putInt("fade", it).apply()
        }
        addSlider(content, 0, 45, skip, { if (it == 0) "No saltar intro" else "Saltar los primeros $it s" }) {
            skip = it
            svc?.skipIntroSec = it
            prefs.edit().putInt("skip", it).apply()
        }
        bpmBtn = btn("Analizar BPM de la canción actual") { detectCurrentBpm() }
        content.addView(bpmBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        bpmStatus = body("BPM: pendiente de análisis")
        content.addView(bpmStatus)
        keyBtn = btn("Analizar tonalidad de la canción actual") { detectCurrentKey() }
        content.addView(keyBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        keyStatus = body("Tonalidad: pendiente de análisis")
        content.addView(keyStatus)
        analysisStatus = body("Biblioteca: esperando permiso para analizar BPM y tonalidad")
        content.addView(analysisStatus)
        genreBtn = btn("Buscar etiquetas de género en Internet") { searchCurrentGenre() }
        content.addView(genreBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        genreStatus = body("Etiquetas: sin consultar")
        content.addView(genreStatus)
        content.addView(body("Géneros: consulta los catálogos públicos de Deezer e iTunes por título y artista. No se envía el archivo de audio. Se muestran etiquetas, no enlaces ni artículos."))
        settingsPage = wrapScroll(content)
    }

    private fun showPage(page: String) {
        currentPage = page
        playerPage.visibility = if (page == "player") android.view.View.VISIBLE else android.view.View.GONE
        mashupPage.visibility = if (page == "mashup") android.view.View.VISIBLE else android.view.View.GONE
        settingsPage.visibility = if (page == "settings") android.view.View.VISIBLE else android.view.View.GONE
        setActive(randomNavBtn, page == "player")
        setActive(mashupNavBtn, page == "mashup")
        setActive(settingsNavBtn, page == "settings")
        if (page == "mashup") updateMashupLabels()
    }

    private fun updateModeButtons() {
        setActive(randomModeBtn, !smartMode)
        setActive(smartModeBtn, smartMode)
    }

    private fun setButtonState(button: Button, state: String) {
        val tint = when (state) {
            "active" -> activeGreen
            "working" -> Color.rgb(255, 193, 7)
            "error" -> Color.rgb(198, 40, 40)
            "unavailable" -> Color.rgb(220, 224, 220)
            else -> neutralTint
        }
        button.backgroundTintList = ColorStateList.valueOf(tint)
        button.setTextColor(if (state == "active" || state == "error") Color.WHITE else neutralText)
    }

    private fun showMashupTrackPicker(forPrimary: Boolean) {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No hay canciones cargadas", Toast.LENGTH_SHORT).show()
            return
        }
        val names = tracks.map { "${it.title} — ${it.artist}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (forPrimary) "Elegir pista A" else "Elegir pista B")
            .setItems(names) { _, which ->
                val selected = tracks[which]
                if (!forPrimary && selectedMashupA?.uri == selected.uri) {
                    Toast.makeText(this, "Elige una canción distinta para la pista B", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                if (forPrimary) {
                    selectedMashupA = selected
                    if (selectedMashupB?.uri == selected.uri) selectedMashupB = null
                } else selectedMashupB = selected
                updateMashupLabels()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun findCompatibleMashupTrack() {
        if (tracks.size < 2) {
            Toast.makeText(this, "Necesitas al menos dos canciones", Toast.LENGTH_SHORT).show()
            return
        }
        val current = svc?.current?.takeIf { t -> tracks.any { it.uri == t.uri } }
        val primary = selectedMashupA ?: current ?: tracks.first()
        selectedMashupA = primary
        val candidates = tracks.filter { it.uri != primary.uri }
        val secondary = candidates.minByOrNull { candidate -> compatibilityCost(primary, candidate) }
        selectedMashupB = secondary
        updateMashupLabels()
        setButtonState(mashupSecondaryBtn, if (secondary != null) "active" else "unavailable")
        mashupStatus.text = if (secondary == null) "No encontré una segunda pista." else
            "Sugerencia calculada con los datos disponibles de BPM y tonalidad. Comprueba la transición escuchándola."
    }

    private fun compatibilityCost(a: Track, b: Track): Double {
        val aId = a.uri.toString()
        val bId = b.uri.toString()
        val abpm = bpmCache[aId]
        val bbpm = bpmCache[bId]
        val akey = keyCache[aId]
        val bkey = keyCache[bId]
        val bpmCost = if (abpm != null && bbpm != null) {
            minOf(kotlin.math.abs(abpm - bbpm), kotlin.math.abs(abpm - bbpm * 2), kotlin.math.abs(abpm * 2 - bbpm)).toDouble() / 10.0
        } else 2.0
        val keyCost = if (akey != null && bkey != null) if (harmonicallyCompatible(akey, bkey)) 0.0 else 3.0 else 1.0
        val ga = genreCache[aId]?.split(" · ")?.map { it.lowercase() }?.toSet().orEmpty()
        val gb = genreCache[bId]?.split(" · ")?.map { it.lowercase() }?.toSet().orEmpty()
        val genreBonus = if (ga.isNotEmpty() && gb.isNotEmpty() && ga.intersect(gb).isNotEmpty()) -0.75 else 0.0
        return bpmCost + keyCost + genreBonus
    }

    private fun startSelectedMashup() {
        val a = selectedMashupA
        val b = selectedMashupB
        if (a == null || b == null || a.uri == b.uri) {
            Toast.makeText(this, "Selecciona dos canciones distintas", Toast.LENGTH_SHORT).show()
            return
        }
        val indexA = tracks.indexOfFirst { it.uri == a.uri }
        val indexB = tracks.indexOfFirst { it.uri == b.uri }
        if (indexA < 0 || indexB < 0) {
            Toast.makeText(this, "Vuelve a seleccionar las pistas de la biblioteca", Toast.LENGTH_SHORT).show()
            return
        }
        val service = svc
        if (service == null) {
            Toast.makeText(this, "El reproductor todavía se está iniciando. Intenta de nuevo.", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, AutomixService::class.java))
        service.playPercent = percent
        service.fadeSec = fade
        service.skipIntroSec = skip
        service.transitionStyle = transitionStyle
        service.startMashup(tracks, indexA, indexB, mashupDurationSec)
        mashupStatus.text = "Mashup iniciado: ${a.title} + ${b.title} · ${mashupDurationSec} s de superposición."
        showPage("mashup")
    }

    private fun updateMashupLabels() {
        if (!::mashupPrimaryBtn.isInitialized) return
        val a = selectedMashupA?.let { "${it.title} — ${it.artist}" } ?: "elegir canción"
        val b = selectedMashupB?.let { "${it.title} — ${it.artist}" } ?: "elegir canción"
        mashupPrimaryBtn.text = "Pista A · $a"
        mashupSecondaryBtn.text = "Pista B · $b"
        setButtonState(mashupPrimaryBtn, if (selectedMashupA != null) "active" else "neutral")
        setButtonState(mashupSecondaryBtn, if (selectedMashupB != null) "active" else "neutral")
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
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            tracks.map { "${it.title} — ${it.artist}" }
        )
        folderBtn.text = if (valid.isEmpty()) "Carpetas: todas" else "Carpetas (${valid.size})"
        setActive(folderBtn, valid.isNotEmpty())
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
                Toast.makeText(this, "Se aplica al pulsar Automix", Toast.LENGTH_SHORT).show()
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
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No hay canciones cargadas", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, AutomixService::class.java))
        svc?.let {
            it.playPercent = percent
            it.fadeSec = fade
            it.skipIntroSec = skip
            it.transitionStyle = transitionStyle
            val selectedUri = tracks.getOrNull(startAt)?.uri
            val source = if (smartMode) smartReorder(tracks) else tracks
            val index = if (selectedUri != null) source.indexOfFirst { track -> track.uri == selectedUri } else -1
            it.start(source, index, shuffle = !smartMode)
            if (smartMode) {
                val known = source.count { track -> bpmCache.containsKey(track.uri.toString()) && keyCache.containsKey(track.uri.toString()) }
                Toast.makeText(this, "Smart Reorder: $known de ${source.size} canciones tienen BPM y tonalidad analizados", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun smartReorder(input: List<Track>): List<Track> {
        val remaining = input.toMutableList()
        if (remaining.size < 3) return remaining
        val known = remaining.filter { bpmCache.containsKey(it.uri.toString()) || keyCache.containsKey(it.uri.toString()) }.toMutableList()
        val unknown = remaining.filterNot { it in known }
        if (known.size < 2) return input
        val ordered = mutableListOf<Track>()
        ordered += known.removeAt(0)
        while (known.isNotEmpty()) {
            val last = ordered.last()
            val lastBpm = bpmCache[last.uri.toString()]
            val lastKey = keyCache[last.uri.toString()]
            val next = known.minByOrNull { candidate ->
                val bpm = bpmCache[candidate.uri.toString()]
                val key = keyCache[candidate.uri.toString()]
                val bpmCost = if (lastBpm != null && bpm != null) minOf(kotlin.math.abs(lastBpm - bpm), kotlin.math.abs(lastBpm - bpm * 2), kotlin.math.abs(lastBpm * 2 - bpm)).toDouble() / 12.0 else 2.0
                val keyCost = if (lastKey != null && key != null) {
                    if (harmonicallyCompatible(lastKey, key)) 0.0 else 3.0
                } else 1.2
                bpmCost + keyCost
            } ?: break
            known.remove(next)
            ordered += next
        }
        ordered += unknown
        return ordered
    }

    private fun harmonicallyCompatible(a: String, b: String): Boolean {
        val camelot = mapOf(
            "C major" to "8B", "G major" to "9B", "D major" to "10B", "A major" to "11B", "E major" to "12B", "B major" to "1B", "F# major" to "2B", "C# major" to "3B", "G# major" to "4B", "D# major" to "5B", "A# major" to "6B", "F major" to "7B",
            "A minor" to "8A", "E minor" to "9A", "B minor" to "10A", "F# minor" to "11A", "C# minor" to "12A", "G# minor" to "1A", "D# minor" to "2A", "A# minor" to "3A", "F minor" to "4A", "C minor" to "5A", "G minor" to "6A", "D minor" to "7A"
        )
        val x = camelot[a] ?: return false
        val y = camelot[b] ?: return false
        val xn = x.dropLast(1).toInt(); val yn = y.dropLast(1).toInt()
        val adjacent = (xn == yn || (xn % 12) + 1 == yn || (yn % 12) + 1 == xn || x.last() != y.last() && xn == yn)
        return adjacent
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
                        }
                    }
                }
            }
            libraryAnalysisRunning = false
            runOnUiThread {
                if (!isFinishing && !isDestroyed) analysisStatus.text = "Análisis terminado · BPM ${bpmCache.size} · tonalidades ${keyCache.size}. Los resultados quedan guardados."
            }
        }.start()
    }

    private fun detectCurrentKey() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        if (keyAnalyzing) return
        keyAnalyzing = true
        keyBtn.isEnabled = false
        keyBtn.text = "Analizando tonalidad…"
        setButtonState(keyBtn, "working")
        keyStatus.text = "Tonalidad: analizando audio local…"
        Thread {
            val key = try { KeyDetector.detect(this, track.uri) } catch (t: Throwable) {
                android.util.Log.e("Automix", "Key analysis failed", t); null
            }
            runOnUiThread {
                keyAnalyzing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                keyBtn.isEnabled = true
                keyBtn.text = "Analizar tonalidad de la canción actual"
                if (key != null) { keyCache[track.uri.toString()] = key; persistAnalysisCaches() }
                if (svc?.current?.uri == track.uri) {
                    keyStatus.text = if (key != null) "Tonalidad estimada: $key (guardada)" else "No pude estimar la tonalidad de esta canción"
                    setButtonState(keyBtn, if (key != null) "active" else "error")
                } else {
                    val currentId = svc?.current?.uri?.toString()
                    setButtonState(keyBtn, if (currentId != null && keyCache.containsKey(currentId)) "active" else "neutral")
                }
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
                prefs.edit().putString("transition_style", transitionStyle).apply()
                setActive(transitionBtn, true)
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun detectCurrentBpm() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        if (bpmAnalyzing) return
        bpmAnalyzing = true
        bpmBtn.isEnabled = false
        bpmBtn.text = "Analizando BPM…"
        setButtonState(bpmBtn, "working")
        bpmStatus.text = "BPM: analizando audio local…"
        Thread {
            val bpm = try { BpmDetector.detect(this, track.uri) } catch (t: Throwable) {
                android.util.Log.e("Automix", "BPM analysis failed", t); null
            }
            runOnUiThread {
                bpmAnalyzing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                bpmBtn.isEnabled = true
                bpmBtn.text = "Analizar BPM de la canción actual"
                if (bpm != null) { bpmCache[track.uri.toString()] = bpm; persistAnalysisCaches() }
                if (svc?.current?.uri == track.uri) {
                    bpmStatus.text = if (bpm != null) "BPM estimado: $bpm (guardado para Smart Reorder)" else "No pude estimar el BPM de esta canción"
                    setButtonState(bpmBtn, if (bpm != null) "active" else "error")
                } else {
                    val currentId = svc?.current?.uri?.toString()
                    setButtonState(bpmBtn, if (currentId != null && bpmCache.containsKey(currentId)) "active" else "neutral")
                }
            }
        }.start()
    }

    private fun searchCurrentGenre() {
        val track = svc?.current
        if (track == null) {
            Toast.makeText(this, "Primero reproduce una canción", Toast.LENGTH_SHORT).show()
            return
        }
        val id = track.uri.toString()
        genreCache[id]?.let {
            genreStatus.text = "Etiquetas: $it"
            genreSummary.text = "Etiquetas: $it"
            setButtonState(genreBtn, "active")
            return
        }
        if (genreLookupRunning) return
        genreLookupRunning = true
        genreBtn.isEnabled = false
        genreBtn.text = "Buscando etiquetas…"
        setButtonState(genreBtn, "working")
        genreStatus.text = "Consultando catálogos musicales…"
        genreSummary.text = "Etiquetas: buscando…"
        Thread {
            val result = lookupGenreOnline(track.title, track.artist)
            runOnUiThread {
                genreLookupRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                genreBtn.isEnabled = true
                genreBtn.text = "Buscar etiquetas de género en Internet"
                if (!result.isNullOrBlank()) {
                    genreCache[id] = result
                    persistGenreCache()
                }
                if (svc?.current?.uri?.toString() == id) {
                    if (!result.isNullOrBlank()) {
                        genreStatus.text = "Etiquetas: $result"
                        genreSummary.text = "Etiquetas: $result"
                        setButtonState(genreBtn, "active")
                    } else {
                        genreStatus.text = "No encontré etiquetas para esta coincidencia. Comprueba el título, artista o conexión."
                        genreSummary.text = "Etiquetas: sin resultados"
                        setButtonState(genreBtn, "error")
                    }
                } else {
                    val currentId = svc?.current?.uri?.toString()
                    val cached = currentId?.let { genreCache[it] }
                    genreSummary.text = cached?.let { "Etiquetas: $it" } ?: "Etiquetas: sin consultar"
                    setButtonState(genreBtn, if (cached != null) "active" else "neutral")
                }
            }
        }.start()
    }

    /** Queries public Deezer and iTunes catalogues; audio never leaves the device. */
    private fun lookupGenreOnline(title: String, artist: String): String? {
        val labels = linkedSetOf<String>()
        try { labels.addAll(lookupDeezerGenres(title, artist)) } catch (e: Exception) {
            android.util.Log.w("Automix", "Deezer genre lookup failed", e)
        }
        if (labels.size < 3) {
            try { labels.addAll(lookupItunesGenres(title, artist)) } catch (e: Exception) {
                android.util.Log.w("Automix", "iTunes genre lookup failed", e)
            }
        }
        return labels.asSequence()
            .map { canonicalGenre(it) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(6)
            .joinToString(" · ")
            .ifBlank { null }
    }

    private fun requestJson(urlText: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "AutomixAndroid/2.0")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) null
            else JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: Exception) {
            android.util.Log.w("Automix", "Catalog request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalizedMatch(value: String): String = value.lowercase()
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun lookupDeezerGenres(title: String, artist: String): List<String> {
        val encoded = URLEncoder.encode("$title $artist", "UTF-8")
        val search = requestJson("https://api.deezer.com/search/track?q=$encoded&limit=10") ?: return emptyList()
        val data = search.optJSONArray("data") ?: return emptyList()
        val wantTitle = normalizedMatch(title)
        val wantArtist = normalizedMatch(artist)
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val candidateTitle = item.optString("title", "")
            val candidateArtist = item.optJSONObject("artist")?.optString("name", "") ?: ""
            val nt = normalizedMatch(candidateTitle)
            val na = normalizedMatch(candidateArtist)
            val titleMatches = nt == wantTitle || (wantTitle.length > 4 && (nt.contains(wantTitle) || wantTitle.contains(nt)))
            val artistMatches = artist.equals("Artista desconocido", true) || na == wantArtist ||
                (wantArtist.length > 3 && (na.contains(wantArtist) || wantArtist.contains(na)))
            if (!titleMatches || !artistMatches) continue
            val score = (if (nt == wantTitle) 3 else 1) + (if (na == wantArtist) 3 else 1)
            if (score > bestScore) { best = item; bestScore = score }
        }
        val albumId = best?.optJSONObject("album")?.optLong("id", 0L) ?: 0L
        if (albumId <= 0L) return emptyList()
        val album = requestJson("https://api.deezer.com/album/$albumId") ?: return emptyList()
        val genres = album.optJSONObject("genres")?.optJSONArray("data") ?: return emptyList()
        val output = mutableListOf<String>()
        for (i in 0 until genres.length()) {
            val name = genres.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
            if (name.isNotBlank()) output.add(name)
        }
        return output
    }

    private fun lookupItunesGenres(title: String, artist: String): List<String> {
        val term = URLEncoder.encode("$title $artist", "UTF-8")
        val json = requestJson("https://itunes.apple.com/search?term=$term&entity=song&limit=15&country=US")
            ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val wantTitle = normalizedMatch(title)
        val wantArtist = normalizedMatch(artist)
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val candidateTitle = item.optString("trackName", "")
            val candidateArtist = item.optString("artistName", "")
            val nt = normalizedMatch(candidateTitle)
            val na = normalizedMatch(candidateArtist)
            val titleMatches = nt == wantTitle || (wantTitle.length > 4 && (nt.contains(wantTitle) || wantTitle.contains(nt)))
            val artistMatches = artist.equals("Artista desconocido", true) || na == wantArtist ||
                (wantArtist.length > 3 && (na.contains(wantArtist) || wantArtist.contains(na)))
            if (!titleMatches || !artistMatches) continue
            val score = (if (nt == wantTitle) 3 else 1) + (if (na == wantArtist) 3 else 1)
            if (score > bestScore) { best = item; bestScore = score }
        }
        val name = best?.optString("primaryGenreName", "")?.trim().orEmpty()
        return if (name.isBlank()) emptyList() else listOf(name)
    }

    private fun canonicalGenre(raw: String): String {
        val n = raw.trim().lowercase()
        return when {
            n.contains("reggaeton") || n.contains("reguetón") -> "Reguetón"
            n.contains("latin urban") || n.contains("urbano latino") -> "Urbano latino"
            n.contains("latin pop") || n.contains("pop latino") -> "Pop latino"
            n.contains("hip-hop") || n.contains("hip hop") || n == "rap" -> "Hip-hop / Rap"
            n.contains("dancehall") -> "Dancehall"
            n.contains("latin") || n.contains("latino") -> "Latino"
            n.contains("electronic") || n.contains("electronica") || n.contains("electrónica") -> "Electrónica"
            n.contains("dance") -> "Dance"
            n.contains("world") -> "Música del mundo"
            n.contains("soundtrack") -> "Banda sonora"
            n.contains("pop") -> "Pop"
            n.contains("rock") -> "Rock"
            n.contains("r&b") || n.contains("rhythm and blues") -> "R&B"
            n.isBlank() -> ""
            else -> raw.trim().replaceFirstChar { it.uppercase() }
        }
    }

    private fun persistGenreCache() {
        val json = JSONObject()
        genreCache.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString("genre_cache", json.toString()).apply()
    }

    private fun refreshNow() {
        val s = svc
        val t = s?.current
        nowTitle.text = t?.title ?: "Elige una canción"
        nowSub.text = t?.artist ?: "Reproductor local · transición automática"
        if (t != null) {
            val id = t.uri.toString()
            bpmStatus.text = bpmCache[id]?.let { "BPM estimado: $it (guardado)" } ?: "BPM: pendiente de análisis"
            keyStatus.text = keyCache[id]?.let { "Tonalidad estimada: $it (guardada)" } ?: "Tonalidad: pendiente de análisis"
            setButtonState(bpmBtn, if (bpmAnalyzing) "working" else if (bpmCache.containsKey(id)) "active" else "neutral")
            setButtonState(keyBtn, if (keyAnalyzing) "working" else if (keyCache.containsKey(id)) "active" else "neutral")
            val genres = genreCache[id]
            if (!genreLookupRunning) setButtonState(genreBtn, if (genres != null) "active" else "neutral")
            genreSummary.text = genres?.let { "Etiquetas: $it" } ?: "Etiquetas: sin consultar"
            if (!genreLookupRunning && ::genreStatus.isInitialized) genreStatus.text = genres?.let { "Etiquetas: $it" } ?: "Etiquetas: sin consultar"
            val upcoming = s?.nextTrack
            nextSongStatus.text = upcoming?.let { "Siguiente: ${it.title} — ${it.artist}" }
                ?: "Siguiente: esperando el orden de reproducción."
        } else {
            bpmStatus.text = "BPM: pendiente de análisis"
            keyStatus.text = "Tonalidad: pendiente de análisis"
            if (!bpmAnalyzing) setButtonState(bpmBtn, "neutral")
            if (!keyAnalyzing) setButtonState(keyBtn, "neutral")
            if (!genreLookupRunning) setButtonState(genreBtn, "neutral")
            genreSummary.text = "Etiquetas: sin consultar"
            nextSongStatus.text = "Siguiente: Automix elegirá según el modo seleccionado."
        }
        pauseBtn.text = if (s?.paused == true) "▶ Reanudar" else "Ⅱ Pausa"
        val playing = t != null
        setActive(automixBtn, playing && s?.paused == false)
        setActive(pauseBtn, s?.paused == true)
        setActive(stopBtn, false)
        setActive(previousBtn, false)
        setActive(nextBtn, false)
    }

    override fun onDestroy() {
        svc?.onChange = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}
