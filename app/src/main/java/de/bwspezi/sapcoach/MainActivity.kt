package de.bwspezi.sapcoach

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Einstellungen auf dem Handy: API-Schlüssel, Quell-PDFs, Testfrage. */
class MainActivity : Activity() {

    companion object { private const val PICK_PDF = 42 }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var docsView: TextView
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var question: EditText
    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun header(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 18f; setPadding(0, pad, 0, pad / 3)
        })
        fun info(t: String) = root.addView(TextView(this).apply { text = t; textSize = 13f })
        fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
            text = t; setOnClickListener { onClick() }
        })

        root.addView(TextView(this).apply { text = "SAP Datasphere Coach"; textSize = 24f })
        info("Sprachgesteuerte Fragen zu SAP Datasphere in Android Auto, beantwortet von Claude auf Basis deiner Quell-PDFs.")

        header("1. Claude-API-Schlüssel")
        info("Von console.anthropic.com → API Keys. Wird nur lokal auf diesem Handy gespeichert.")
        apiKey = EditText(this).apply {
            hint = "sk-ant-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.apiKey(this@MainActivity))
        }
        root.addView(apiKey)
        model = EditText(this).apply {
            hint = "Modell (Standard: ${Prefs.DEFAULT_MODEL})"
            setText(Prefs.model(this@MainActivity))
        }
        root.addView(model)
        button("Speichern") {
            Prefs.setApiKey(this, apiKey.text.toString())
            Prefs.setModel(this, model.text.toString())
            toast("Gespeichert")
        }

        header("2. Quelldokumente (PDF)")
        info("Wähle die vereinbarten PDFs aus. Sie werden einmalig zu deinem Claude-API-Konto hochgeladen und bei jeder Frage mitgeschickt.")
        docsView = TextView(this)
        root.addView(docsView)
        button("PDFs hinzufügen") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(i, PICK_PDF)
        }
        button("Alle Dokumente entfernen") {
            Prefs.clearDocs(this); refreshDocs()
        }

        header("3. Testen")
        question = EditText(this).apply {
            hint = "z. B. Was ist der Unterschied zwischen Analytic Model und Fact View?"
        }
        root.addView(question)
        button("Frage stellen (mit Sprachausgabe)") {
            val q = question.text.toString().trim()
            if (q.isEmpty()) toast("Bitte eine Frage eingeben")
            else controller?.transportControls?.playFromSearch(q, Bundle()) ?: toast("Dienst noch nicht verbunden")
        }
        button("Sprachausgabe stoppen") { controller?.transportControls?.stop() }
        button("Gesprächsverlauf löschen") { Prefs.clearHistory(this); toast("Verlauf gelöscht") }

        header("Im Auto")
        info(
            "Einmalig: In Android Auto → Entwicklereinstellungen → „Unbekannte Quellen“ aktivieren und im Launcher „SAP Coach“ einblenden.\n\n" +
            "Fragen: „Hey Google, spiele <deine Frage> auf SAP Coach.“\n" +
            "Oder SAP Coach in Android Auto öffnen und auf Mikrofon bzw. Suche tippen.\n" +
            "Im Menü der App: letzte Antwort wiederholen, frühere Antworten erneut hören, neues Gespräch beginnen."
        )

        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(status)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refreshDocs()
    }

    override fun onStart() {
        super.onStart()
        val b = MediaBrowserCompat(this, ComponentName(this, CoachService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    controller = MediaControllerCompat(this@MainActivity, browser!!.sessionToken)
                }
            }, null)
        browser = b
        b.connect()
    }

    override fun onStop() {
        browser?.disconnect()
        browser = null
        controller = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PDF || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (Prefs.apiKey(this).isBlank() && apiKey.text.isNotBlank()) Prefs.setApiKey(this, apiKey.text.toString())
        upload(uris)
    }

    private fun upload(uris: List<Uri>) {
        setStatus("Lade ${uris.size} PDF(s) hoch …")
        worker.execute {
            val client = ClaudeClient(this)
            var ok = 0
            val errors = mutableListOf<String>()
            uris.forEach { uri ->
                val name = displayName(uri)
                try {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val id = client.uploadPdf(name, bytes)
                    Prefs.addDoc(this, SourceDoc(id, name))
                    ok++
                } catch (e: Exception) {
                    errors.add("$name: ${e.message}")
                }
            }
            runOnUiThread {
                refreshDocs()
                setStatus("$ok hochgeladen." + if (errors.isEmpty()) "" else "\nFehler:\n" + errors.joinToString("\n"))
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "dokument.pdf"
        }
        return "dokument.pdf"
    }

    private fun refreshDocs() {
        val docs = Prefs.docs(this)
        docsView.text = if (docs.isEmpty()) "Noch keine Dokumente hinterlegt."
        else docs.joinToString("\n") { "• " + it.name }
    }

    private fun setStatus(t: String) { status.text = t }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
