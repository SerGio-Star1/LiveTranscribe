package com.example.livetranscribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener as SpeechListener
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private val sampleRate = 16000.0f
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var useAndroid = true

    private val finalText = StringBuilder()
    private var partial = ""

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnToggle: Button
    private lateinit var btnEngine: Button

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

        btnEngine = Button(this)
        (status.parent as LinearLayout).addView(btnEngine, 1)
        btnEngine.setOnClickListener {
            if (listening) { toast("Erst Aufnahme stoppen"); return@setOnClickListener }
            useAndroid = !useAndroid
            refreshUi()
        }

        btnToggle.setOnClickListener { if (listening) stopEngine() else requestAndStart() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            finalText.clear(); partial = ""; render()
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
        else "Engine: Vosk (eigenes Modell)  – tippen zum Wechseln"
        btnToggle.isEnabled = useAndroid || model != null
        status.text = "Bereit"
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

    private fun startEngine() {
        if (useAndroid) startAndroid() else startVosk()
    }

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
        partial = ""
        btnToggle.text = "Aufnahme starten"
        status.text = "Bereit"
        render()
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
        btnToggle.text = "Aufnahme stoppen"
        status.text = "Hört zu (Android)…"
        listenOnce()
    }

    private fun listenOnce() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
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
            if (t.isNotBlank()) finalText.append(t).append("\n")
            partial = ""
            render()
            if (listening) restartAndroid()
        }

        override fun onError(error: Int) {
            partial = ""
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (listening) restartAndroid()
                12, 13 -> { toast("Deutsches Offline-Sprachpaket fehlt (siehe Anleitung)"); stopEngine() }
                else -> { toast("Fehler Code $error"); stopEngine() }
            }
        }
    }

    // ---------- Vosk-Engine ----------
    private fun startVosk() {
        val m = model ?: return
        try {
            speechService = SpeechService(Recognizer(m, sampleRate), sampleRate).also {
                it.startListening(this)
            }
            listening = true
            btnToggle.text = "Aufnahme stoppen"
            status.text = "Hört zu (Vosk)…"
        } catch (e: Exception) {
            toast("Fehler: ${e.message}")
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        partial = JSONObject(hypothesis ?: "{}").optString("partial")
        render()
    }

    override fun onResult(hypothesis: String?) {
        val t = JSONObject(hypothesis ?: "{}").optString("text")
        if (t.isNotBlank()) finalText.append(t).append("\n")
        partial = ""
        render()
    }

    override fun onFinalResult(hypothesis: String?) = onResult(hypothesis)
    override fun onError(e: Exception?) { toast("Fehler: ${e?.message}") }
    override fun onTimeout() {}

    // ---------- UI & Datei ----------
    private fun currentText() = finalText.toString() + partial

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
        recognizer?.destroy()
        speechService?.shutdown()
        model?.close()
    }
}