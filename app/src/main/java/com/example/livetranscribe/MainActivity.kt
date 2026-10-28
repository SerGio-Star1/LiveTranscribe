package com.example.livetranscribe

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
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
    private var listening = false

    private val finalText = StringBuilder()
    private var partial = ""

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnToggle: Button

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else toast("Mikrofon-Berechtigung nötig")
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

        btnToggle.setOnClickListener { if (listening) stopListening() else requestAndStart() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            finalText.clear(); partial = ""; render()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (currentText().isBlank()) { toast("Kein Text vorhanden"); return@setOnClickListener }
            val name = "Transkript_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date()) + ".txt"
            saveFile.launch(name)
        }
        loadModel()
    }

    /** Kopiert das Modell einmalig aus den Assets in den internen Speicher und lädt es. */
    private fun loadModel() {
        thread {
            try {
                val target = File(filesDir, "model-de")
                if (!File(target, "am/final.mdl").exists()) {
                    runOnUiThread { status.text = "Modell wird erstmalig entpackt…" }
                    target.deleteRecursively()
                    copyAssetDir("model-de", target)
                }
                model = Model(target.absolutePath)
                runOnUiThread {
                    status.text = "Bereit (offline)"
                    btnToggle.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Fehler beim Laden: ${e.message}" }
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
            == PackageManager.PERMISSION_GRANTED) startListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        val m = model ?: return
        try {
            speechService = SpeechService(Recognizer(m, sampleRate), sampleRate).also {
                it.startListening(this)
            }
            listening = true
            btnToggle.text = "Aufnahme stoppen"
            status.text = "Hört zu…"
        } catch (e: Exception) {
            toast("Fehler: ${e.message}")
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        listening = false
        partial = ""
        btnToggle.text = "Aufnahme starten"
        status.text = "Bereit (offline)"
        render()
    }

    // --- Vosk-Callbacks ---
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

    // --- UI & Datei ---
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
        speechService?.shutdown()
        model?.close()
    }
}
