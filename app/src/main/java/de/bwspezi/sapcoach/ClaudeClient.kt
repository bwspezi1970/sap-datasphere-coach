package de.bwspezi.sapcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Schlanker Client für die Claude-API (Messages + Files), ohne Zusatzbibliotheken. */
class ClaudeClient(private val ctx: Context) {

    companion object {
        private const val BASE = "https://api.anthropic.com/v1"
        private const val VERSION = "2023-06-01"
        private const val HISTORY_TURNS = 8

        val SYSTEM_PROMPT = """
Du bist ein Sprach-Coach für SAP Datasphere. Der Nutzer fährt gerade Auto und hört deine Antwort über eine Sprachausgabe.

Inhaltliche Regeln:
- Antworte auf Deutsch.
- Zielgruppe ist ein SAP-Entwickler mit Grundkenntnissen in SAP Datasphere.
- Stütze dich auf die bereitgestellten Quelldokumente. Gib nur eine Antwort, wenn du dir sicher bist. Wenn die Quellen die Frage nicht abdecken und du unsicher bist, sag das kurz und klar, statt zu raten. Wenn du ergänzend Allgemeinwissen nutzt, sag, dass es nicht aus den Quellen stammt.
- Weise, wo sinnvoll, auf Analogien zu SAP BW/4HANA hin und benenne immer auch die Grenzen der Analogie bzw. die Unterschiede.
- Beziehe dich, wenn passend, auf frühere Fragen aus diesem Gespräch.

Format für die Sprachausgabe:
- Nur ganze, gut sprechbare Sätze. Kein Markdown, keine Aufzählungszeichen, keine Tabellen, keine Code-Blöcke, keine Emojis, keine URLs.
- Abkürzungen beim ersten Auftreten kurz erklären.
- In der Regel 120 bis 250 Wörter, also etwa ein bis zwei Minuten Sprechzeit. Biete am Ende bei Bedarf an, einen Aspekt zu vertiefen.
- Die Frage stammt aus einer Spracherkennung und kann Erkennungsfehler enthalten, etwa "Data Sphere" oder "BW for HANA". Interpretiere sie sinnvoll.
""".trim()
    }

    class ApiException(msg: String) : Exception(msg)

    private fun open(path: String, method: String): HttpURLConnection {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) throw ApiException("Kein API-Schlüssel hinterlegt. Bitte in der App auf dem Handy eintragen.")
        val c = URL("$BASE$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 180_000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", VERSION)
        return c
    }

    private fun readResponse(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(body).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                body.take(300)
            }
            throw ApiException("API-Fehler $code: $msg")
        }
        return JSONObject(body)
    }

    /** Lädt ein PDF in die Files API hoch und gibt die Datei-ID zurück. */
    fun uploadPdf(fileName: String, bytes: ByteArray): String {
        val boundary = "----SapCoach" + System.currentTimeMillis()
        val c = open("/files", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val safeName = fileName.replace("\"", "")
        val out = ByteArrayOutputStream()
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: application/pdf\r\n\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        c.setFixedLengthStreamingMode(out.size())
        c.outputStream.use { it.write(out.toByteArray()) }
        return readResponse(c).getString("id")
    }

    /** Stellt eine Frage mit Quelldokumenten und bisherigem Verlauf. Blockierend – nicht im UI-Thread aufrufen. */
    fun ask(question: String): String {
        val docs = Prefs.docs(ctx)
        val history = Prefs.history(ctx).takeLast(HISTORY_TURNS)
        val messages = JSONArray()

        if (docs.isNotEmpty()) {
            val content = JSONArray()
            docs.forEach { d ->
                content.put(
                    JSONObject()
                        .put("type", "document")
                        .put("source", JSONObject().put("type", "file").put("file_id", d.fileId))
                        .put("title", d.name)
                )
            }
            // Cache-Marke nach den Dokumenten: Folgefragen innerhalb einer Stunde werden günstiger.
            content.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", "Das sind die vereinbarten Quelldokumente zu SAP Datasphere. Beantworte meine folgenden Fragen auf ihrer Basis.")
                    .put("cache_control", JSONObject().put("type", "ephemeral").put("ttl", "1h"))
            )
            messages.put(JSONObject().put("role", "user").put("content", content))
            messages.put(JSONObject().put("role", "assistant").put("content", "Verstanden. Ich beantworte deine Fragen auf Basis dieser Dokumente."))
        }

        history.forEach { e ->
            messages.put(JSONObject().put("role", "user").put("content", e.question))
            messages.put(JSONObject().put("role", "assistant").put("content", e.answer))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        val system = if (docs.isEmpty())
            SYSTEM_PROMPT + "\n\nHinweis: Es sind derzeit keine Quelldokumente hinterlegt. Sag das zu Beginn deiner Antwort kurz."
        else SYSTEM_PROMPT

        val req = JSONObject()
            .put("model", Prefs.model(ctx))
            .put("max_tokens", 1500)
            .put("system", system)
            .put("messages", messages)

        val c = open("/messages", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val bytes = req.toString().toByteArray(Charsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }

        val resp = readResponse(c)
        val parts = resp.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optString("type") == "text") sb.append(p.getString("text"))
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) throw ApiException("Leere Antwort erhalten.")
        return text
    }
}
