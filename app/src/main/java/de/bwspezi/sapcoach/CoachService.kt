package de.bwspezi.sapcoach

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Android-Auto-Medienservice. Fragen kommen als Sprachsuche
 * ("Hey Google, spiele <Frage> auf SAP Coach") über onPlayFromSearch herein,
 * die Antwort von Claude wird als "Wiedergabe" vorgelesen.
 */
class CoachService : MediaBrowserServiceCompat() {

    companion object {
        private const val ROOT = "root"
        private const val ID_HELP = "help"
        private const val ID_REPEAT = "repeat"
        private const val ID_NEW = "new"
        private const val ID_HISTORY_PREFIX = "h_"

        const val HELP_TEXT = "Stell mir eine Frage, indem du sagst: Hey Google, spiele, und dann deine Frage, auf SAP Coach. " +
            "Zum Beispiel: Hey Google, spiele Was ist ein Analytic Model, auf SAP Coach. " +
            "Alternativ öffnest du SAP Coach in Android Auto und tippst auf das Mikrofon oder die Suche."
    }

    private lateinit var session: MediaSessionCompat
    private lateinit var speaker: Speaker
    private lateinit var client: ClaudeClient
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var requestSeq = 0
    private var lastSpoken: String? = null

    private val actions = PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_STOP or
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID

    override fun onCreate() {
        super.onCreate()
        client = ClaudeClient(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        speaker = Speaker(this) { main.post { onSpeechFinished() } }

        session = MediaSessionCompat(this, "SapCoach")
        session.setCallback(callback)
        sessionToken = session.sessionToken
        setState(PlaybackStateCompat.STATE_STOPPED)
        setMetadata("SAP Coach", "Frag mich etwas zu SAP Datasphere")
        session.isActive = true
    }

    override fun onDestroy() {
        requestSeq++
        speaker.shutdown()
        abandonFocus()
        session.isActive = false
        session.release()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------- Medienbaum für Android Auto ----------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != ROOT) { result.sendResult(mutableListOf()); return }
        val items = mutableListOf(
            item(ID_HELP, "So funktioniert's", "Kurze Anleitung"),
            item(ID_REPEAT, "Letzte Antwort wiederholen", null),
            item(ID_NEW, "Neues Gespräch beginnen", "Verlauf löschen")
        )
        val fmt = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY)
        val history = Prefs.history(this)
        history.withIndex().reversed().take(15).forEach { (i, e) ->
            items.add(item(ID_HISTORY_PREFIX + i, e.question, fmt.format(Date(e.timestamp))))
        }
        result.sendResult(items)
    }

    private fun item(id: String, title: String, subtitle: String?): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    // ---------- Steuerung ----------

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val q = query?.trim().orEmpty()
            if (q.isEmpty()) say("Hilfe", HELP_TEXT) else askClaude(q)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when {
                mediaId == ID_HELP -> say("Hilfe", HELP_TEXT)
                mediaId == ID_REPEAT -> repeatLast()
                mediaId == ID_NEW -> {
                    Prefs.clearHistory(this@CoachService)
                    notifyChildrenChanged(ROOT)
                    say("Neues Gespräch", "Neues Gespräch gestartet. Der bisherige Verlauf ist gelöscht.")
                }
                mediaId != null && mediaId.startsWith(ID_HISTORY_PREFIX) -> {
                    val idx = mediaId.removePrefix(ID_HISTORY_PREFIX).toIntOrNull()
                    val e = idx?.let { Prefs.history(this@CoachService).getOrNull(it) }
                    if (e != null) say(e.question, e.answer) else say("Hilfe", HELP_TEXT)
                }
            }
        }

        override fun onPlay() = repeatLast()

        override fun onPause() {
            requestSeq++
            speaker.stop()
            abandonFocus()
            setState(PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onStop() {
            requestSeq++
            speaker.stop()
            abandonFocus()
            setState(PlaybackStateCompat.STATE_STOPPED)
        }
    }

    private fun repeatLast() {
        val text = lastSpoken ?: Prefs.history(this).lastOrNull()?.answer
        if (text != null) say("Wiederholung", text) else say("Hilfe", HELP_TEXT)
    }

    private fun askClaude(question: String) {
        val seq = ++requestSeq
        setMetadata(question, "Claude denkt nach …")
        setState(PlaybackStateCompat.STATE_BUFFERING)
        requestFocus()
        speaker.speak("Einen Moment, ich schaue nach.")
        worker.execute {
            val answer = try {
                val a = client.ask(question)
                Prefs.addExchange(this, Exchange(question, a, System.currentTimeMillis()))
                a
            } catch (e: Exception) {
                "Das hat leider nicht geklappt. " + (e.message ?: e.javaClass.simpleName)
            }
            main.post {
                if (seq != requestSeq) return@post // inzwischen neue Frage oder Stopp
                notifyChildrenChanged(ROOT)
                say(question, answer)
            }
        }
    }

    private fun say(title: String, text: String) {
        lastSpoken = text
        setMetadata(title, "SAP Coach")
        requestFocus()
        speaker.speak(text)
        setState(PlaybackStateCompat.STATE_PLAYING)
    }

    private fun onSpeechFinished() {
        val s = session.controller.playbackState?.state
        if (s == PlaybackStateCompat.STATE_PLAYING) {
            abandonFocus()
            setState(PlaybackStateCompat.STATE_STOPPED)
        }
    }

    // ---------- Hilfsfunktionen ----------

    private fun setState(state: Int) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun setMetadata(title: String, subtitle: String) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .build()
        )
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(speaker.attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) main.post { callback.onStop() }
            }
            .build()
        audioManager.requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
