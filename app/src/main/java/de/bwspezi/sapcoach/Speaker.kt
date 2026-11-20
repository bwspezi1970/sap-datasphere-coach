package de.bwspezi.sapcoach

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Deutsche Sprachausgabe über die Medien-Audiospur (läuft so über die Autolautsprecher). */
class Speaker(ctx: Context, private val onFinished: () -> Unit) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false
    private var pending: String? = null

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.GERMANY
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == LAST) onFinished()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == LAST) onFinished()
            }
        })
        ready = true
        pending?.let { pending = null; speak(it) }
    }

    fun speak(text: String) {
        val clean = cleanForSpeech(text)
        if (!ready) { pending = clean; return }
        val chunks = chunk(clean, minOf(TextToSpeech.getMaxSpeechInputLength() - 100, 3000))
        chunks.forEachIndexed { i, part ->
            val id = if (i == chunks.lastIndex) LAST else "part$i"
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        text.split(Regex("(?<=[.!?])\\s+")).forEach { sentence ->
            if (sb.length + sentence.length + 1 > max && sb.isNotEmpty()) {
                result.add(sb.toString()); sb.clear()
            }
            sb.append(sentence).append(' ')
        }
        if (sb.isNotBlank()) result.add(sb.toString())
        return result
    }

    companion object {
        private const val LAST = "last"

        /** Entfernt Markdown-Reste, die vorgelesen merkwürdig klingen würden. */
        fun cleanForSpeech(s: String): String = s
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_#`>|]"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace("BW/4HANA", "BW vier HANA")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
