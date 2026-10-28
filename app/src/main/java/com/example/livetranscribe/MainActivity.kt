package com.example.livetranscribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener as SpeechListener
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), RecognitionListener {

    data class Lang(val name: String, val mlCode: String, val locale: String)
    class Entry(val src: String, var de: String? = null)

    private val langs = listOf(
        Lang("Deutsch (keine Übersetzung)", TranslateLanguage.GERMAN, "de-DE"),
        Lang("Türkisch → Deutsch", TranslateLanguage.TURKISH, "tr-TR"),
        Lang("Koreanisch → Deutsch", TranslateLanguage.KOREAN, "ko-KR")
    )
    private var lang = langs[0]

    private val sampleRate = 16000.0f
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var useAndroid = true
    private var muted = false

    private val entries = mutableListOf<Entry>()
    private var partial = ""

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnToggle: Button
    private lateinit var btnEngine: Button
    private lateinit var spinner: Spinner

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startEngine() else toast("Mikrofon-Berechtigung nötig")
        }

    private val saveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeText(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        output = findViewById(R.id.output)
        scroll = findViewById(R.id.scroll)
        btnToggle = findViewById(R.id.btnToggle)

        val root = status.parent as LinearLayout
        btnEngine = Button(this)
        root.addView(btnEngine, 1)
        spinner = Spinner(this)
        root.addView(spinner, 2)

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs.map { it.name })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                lang = langs[pos]
                setupTranslator()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnEngine.setOnClickListener {
            if (listening) { toast("Erst Aufnahme stoppen"); return@setOnClickListener }
            useAndroid = !useAndroid
            if (!useAndroid) spinner.setSelection(0)
            refreshUi()
        }
        btnToggle.setOnClickListener { if (listening) stopEngine() else requestAndStart() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            entries.clear(); partial = ""; render()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (currentText().isBlank()) { toast("Kein Text vorhanden"); return@setOnClickListener }
            val name = "Transkript_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date()) + ".txt"
            saveFile.launch(name)
        }
        refreshUi()
        loadModel()
    }

    private fun refreshUi() {
        btnEngine.text = if (useAndroid) "Engine: Android/Google (offline)  – tippen zum Wechseln"
        else "Engine: Vosk (nur Deutsch)  – tippen zum Wechseln"
        btnToggle.isEnabled = useAndroid || model != null
        spinner.isEnabled = useAndroid && !listening
    }

    /** Lädt das Übersetzungsmodell (einmalig mit Internet nötig). */
    private fun setupTranslator() {
        translator?.close()
        translator = null
        if (lang.mlCode == TranslateLanguage.GERMAN) { status.text = "Bereit"; return }
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(lang.mlCode)
            .setTargetLanguage(TranslateLanguage.GERMAN)
            .build()
        val tr = Translation.getClient(opts)
        translator = tr
        status.text = "Übersetzungsmodell wird geladen…"
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { status.text = "Bereit (Übersetzung offline)" }
            .addOnFailureListener { status.text = "Modell fehlt – einmal mit Internet öffnen" }
    }

    private fun loadModel() {
        thread {
            try {
                val target = File(filesDir, "model-de")
                if (!File(target, "am/final.mdl").exists()) {
                    target.deleteRecursively()
                    copyAssetDir("model-de", target)
                }
                model = Model(target.absolutePath)
                runOnUiThread { refreshUi() }
            } catch (e: Exception) {
                runOnUiThread { toast("Vosk-Modell Fehler: ${e.message}") }
            }
        }
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (c in children) copyAssetDir("$assetPath/$c", File(dest, c))
        }
    }

    private fun requestAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startEngine()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startEngine() { if (useAndroid) startAndroid() else startVosk() }

    private fun stopEngine() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        if (useAndroid) {
            val r = recognizer
            recognizer = null
            r?.stopListening()
            handler.postDelayed({ r?.destroy() }, 1500)
        } else {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
        }
        muteBeep(false)
        partial = ""
        btnToggle.text = "Aufnahme starten"
        status.text = "Bereit"
        refreshUi()
        render()
    }

    /** Schaltet die Medienlautstärke stumm, damit der Erkennungs-Piepton nicht zu hören ist. */
    private fun muteBeep(on: Boolean) {
        if (on == muted) return
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0
            )
            muted = on
        } catch (e: Exception) { }
    }

    // ---------- Android/Google-Engine ----------
    private fun startAndroid() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Keine Spracherkennung auf diesem Gerät gefunden"); return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(androidListener)
        }
        listening = true
        muteBeep(true)
        btnToggle.text = "Aufnahme stoppen"
        status.text = "Hört zu (${lang.locale})…"
        refreshUi()
        listenOnce()
    }

    private fun listenOnce() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.locale)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(i)
    }

    private fun restartAndroid() {
        handler.postDelayed({
            if (listening && useAndroid) {
                recognizer?.cancel()
                listenOnce()
            }
        }, 300)
    }

    private val androidListener = object : SpeechListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            render()
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            partial = ""
            addEntry(t)
            if (listening) restartAndroid()
        }

        override fun onError(error: Int) {
            partial = ""
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (listening) restartAndroid()
                12, 13 -> { toast("Offline-Sprachpaket für ${lang.name} fehlt"); stopEngine() }
                else -> { toast("Fehler Code $error"); stopEngine() }
            }
        }
    }

    private fun addEntry(t: String) {
        if (t.isBlank()) { render(); return }
        val e = Entry(t)
        entries.add(e)
        val tr = translator
        if (tr != null) {
            tr.translate(t)
                .addOnSuccessListener { e.de = it; render() }
                .addOnFailureListener { e.de = "(Übersetzung fehlgeschlagen)"; render() }
        }
        render()
    }

    // ---------- Vosk-Engine (nur Deutsch) ----------
    private fun startVosk() {
        val m = model ?: return
        try {
            speechService = SpeechService(Recognizer(m, sampleRate), sampleRate).also {
                it.startListening(this)
            }
            listening = true
            btnToggle.text = "Aufnahme stoppen"
            status.text = "Hört zu (Vosk)…"
            refreshUi()
        } catch (e: Exception) {
            toast("Fehler: ${e.message}")
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        partial = JSONObject(hypothesis ?: "{}").optString("partial")
        render()
    }

    override fun onResult(hypothesis: String?) {
        partial = ""
        addEntry(JSONObject(hypothesis ?: "{}").optString("text"))
    }

    override fun onFinalResult(hypothesis: String?) = onResult(hypothesis)
    override fun onError(e: Exception?) { toast("Fehler: ${e?.message}") }
    override fun onTimeout() {}

    // ---------- UI & Datei ----------
    private fun currentText(): String =
        entries.joinToString("") {
            if (it.de != null) "${it.src}\n→ ${it.de}\n\n" else "${it.src}\n"
        } + partial

    private fun render() {
        runOnUiThread {
            output.text = currentText()
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun writeText(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(currentText().toByteArray(Charsets.UTF_8)) }
            toast("Gespeichert")
        } catch (e: Exception) {
            toast("Speichern fehlgeschlagen: ${e.message}")
        }
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        muteBeep(false)
        recognizer?.destroy()
        speechService?.shutdown()
        translator?.close()
        model?.close()
    }
}
