package com.example.teethree.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.security.MessageDigest

/** What the user chose to do with a finished capture. */
internal enum class VoiceFinish {
    Send,
    Edit,
}

/**
 * Whether the composer leads with the voice dock or the text field (persisted per device), and
 * whether hands-free is on (kept for the session, across chats and fold postures).
 */
@Immutable
internal data class ComposerInputMode(
    val voice: Boolean,
    val onChange: (Boolean) -> Unit,
    val handsFree: Boolean = false,
    val onHandsFreeChange: (Boolean) -> Unit = {},
)

internal val LocalComposerInputMode = staticCompositionLocalOf {
    ComposerInputMode(voice = true, onChange = {})
}

/**
 * In-app speech capture with live partial results and input level, so the transcript and waveform
 * render inside the dock instead of the system dictation dialog.
 *
 * A tap captures one utterance and finishes when the speaker pauses ("pause to send"). Holding the
 * mic keeps restarting the recognizer across pauses until release (push-to-talk).
 */
@Stable
internal class VoiceInput(private val context: Context) {
    var listening by mutableStateOf(false)
        private set

    /** Segments the recognizer has committed during this capture. */
    var transcript by mutableStateOf("")
        private set

    /** The segment still being recognized; it may change until committed. */
    var partial by mutableStateOf("")
        private set

    var startedAt by mutableLongStateOf(0L)
        private set

    var error by mutableStateOf<String?>(null)
        internal set

    /** How long the last delivered capture ran; 0 when it came from the system dialog. */
    var lastDurationMs = 0L
        private set

    /** Recent input levels, 0..1, oldest first. */
    val levels = mutableStateListOf<Float>().apply { repeat(LevelCount) { add(0f) } }

    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null
    private var holding = false
    private var finish = VoiceFinish.Send
    private var onDone: ((String, VoiceFinish) -> Unit)? = null

    private val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Leave room for a breath mid-sentence before "pause to send" kicks in.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            .putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1600L,
            )

    fun start(hold: Boolean = false, onDone: (String, VoiceFinish) -> Unit) {
        cancel()
        this.onDone = onDone
        holding = hold
        finish = VoiceFinish.Send
        transcript = ""
        partial = ""
        error = null
        for (index in levels.indices) levels[index] = 0f
        startedAt = SystemClock.elapsedRealtime()
        listening = true
        listen()
    }

    /** Stops capture; what was heard is delivered once the recognizer commits it. */
    fun finish(action: VoiceFinish = VoiceFinish.Send) {
        if (!listening) return
        finish = action
        holding = false
        recognizer?.stopListening()
    }

    /** Discards the capture without delivering anything. */
    fun cancel() {
        onDone = null
        holding = false
        if (listening) recognizer?.cancel()
        listening = false
        transcript = ""
        partial = ""
    }

    fun dispose() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        val active =
            recognizer
                ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(listener)
                    recognizer = it
                }
        active.startListening(intent)
    }

    /** Marks a capture that the system dictation dialog made, so it has no duration. */
    fun noteExternalCapture() {
        lastDurationMs = 0L
    }

    private fun deliver() {
        lastDurationMs = SystemClock.elapsedRealtime() - startedAt
        val text = joinSpeech(transcript, partial)
        val callback = onDone
        onDone = null
        holding = false
        listening = false
        partial = ""
        callback?.invoke(text, finish)
    }

    private fun push(level: Float) {
        levels.removeAt(0)
        levels.add(level.coerceIn(0f, 1f))
    }

    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = push((rmsdB + 2f) / 12f)

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstResult()?.let { partial = it }
            }

            override fun onResults(results: Bundle?) {
                if (!listening) return
                transcript = joinSpeech(transcript, results.firstResult() ?: partial)
                partial = ""
                if (holding) listen() else deliver()
            }

            override fun onError(code: Int) {
                if (!listening) return
                when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT -> if (holding) listen() else deliver()
                    else -> {
                        error = speechErrorMessage(code)
                        onDone = null
                        holding = false
                        listening = false
                        partial = ""
                    }
                }
            }
        }

    companion object {
        const val LevelCount = 24
    }
}

private fun Bundle?.firstResult(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.takeIf(String::isNotBlank)

internal fun joinSpeech(vararg parts: String) =
    parts.map(String::trim).filter(String::isNotEmpty).joinToString(" ")

private fun speechErrorMessage(code: Int) =
    when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition needs a connection right now."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn't record."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech recognition isn't set up for this language."
        else -> "Speech recognition stopped. Try again."
    }

/** A capture that ends in "cancel" is discarded, as the listening hint promises. */
internal fun isDiscardPhrase(text: String): Boolean {
    val words = speechWords(text)
    return words.lastOrNull() == "cancel" ||
        words.takeLast(2) == listOf("cancel", "that") ||
        words == listOf("discard")
}

private fun speechWords(text: String) =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter(String::isNotEmpty)

/** The spoken word that picks each approval decision, as shown on the approval card. */
internal fun approvalKeyword(decision: String): String? =
    when (decision) {
        "accept" -> "allow"
        "acceptForSession",
        "acceptAlways" -> "always"
        "decline" -> "no"
        "cancel" -> "stop"
        else -> null
    }

/**
 * Maps a short spoken answer to one of the offered decisions. Anything longer than a few words is
 * treated as a message to the agent instead, so steering never approves by accident.
 */
internal fun approvalDecision(text: String, decisions: List<String>): String? {
    val words = speechWords(text).filterNot { it in setOf("please", "it", "that", "ok", "okay") }
    if (words.isEmpty() || words.size > 3) return null
    val always = words.any { it == "always" }
    val wanted =
        when {
            always -> listOf("acceptForSession", "acceptAlways")
            words.first() in setOf("allow", "yes", "yeah", "yep", "approve", "accept", "sure") ->
                listOf("accept")
            words.first() in setOf("no", "nope", "decline", "deny", "reject", "don't") ->
                listOf("decline")
            words.first() == "stop" -> listOf("cancel")
            else -> emptyList()
        }
    return wanted.firstOrNull { it in decisions }
}

/** Plain text for reading a markdown reply aloud: code is skipped, markup is dropped. */
internal fun speechText(markdown: String): String =
    markdown
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+[.)])\\s+"), "")
        .replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$"), " ")
        .replace(Regex("[*_~|]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

@Composable
internal fun rememberVoiceInput(): VoiceInput {
    val context = LocalContext.current.applicationContext
    val voice = remember(context) { VoiceInput(context) }
    DisposableEffect(voice) { onDispose { voice.dispose() } }
    return voice
}

/**
 * Starts a capture after making sure the app may record. Without the permission (or an in-app
 * recognizer) it falls back to the system dictation dialog, which needs neither.
 */
@Composable
internal fun rememberVoiceStarter(
    voice: VoiceInput,
    onResult: (String, VoiceFinish) -> Unit,
): (hold: Boolean) -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    val dictation =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK)
                it.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.let { text ->
                        voice.noteExternalCapture()
                        latest.value(text, VoiceFinish.Send)
                    }
        }
    val systemDictation = {
        try {
            dictation.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                    context,
                    "Install a speech recognition service to talk to the agent.",
                    Toast.LENGTH_LONG,
                )
                .show()
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voice.start { text, finish -> latest.value(text, finish) }
            else systemDictation()
        }
    return remember(voice) {
        { hold ->
            when {
                !voice.available -> systemDictation()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED ->
                    voice.start(hold) { text, finish -> latest.value(text, finish) }
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

internal fun hasMicPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** Reads agent replies aloud in hands-free mode. */
@Stable
internal class Speaker(context: Context) {
    var speakingId by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var queued: Triple<String, String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null
    private val tts: TextToSpeech =
        TextToSpeech(context.applicationContext) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                queued?.let { (id, text, done) -> if (ready) speak(id, text, done) else done() }
                queued = null
            }
        }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    finished(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    finished(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finished(utteranceId)
                }
            }
        )
    }

    fun speak(id: String, text: String, onDone: () -> Unit) {
        if (!ready) {
            queued = Triple(id, text, onDone)
            speakingId = id
            return
        }
        this.onDone = onDone
        speakingId = id
        val limit = TextToSpeech.getMaxSpeechInputLength() - 1
        tts.speak(text.take(limit), TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Stops reading and continues as if the reply had finished (the Skip button). */
    fun skip() {
        val done = onDone ?: queued?.third
        onDone = null
        queued = null
        speakingId = null
        tts.stop()
        done?.invoke()
    }

    /** Stops reading without continuing, such as when hands-free is turned off. */
    fun silence() {
        onDone = null
        queued = null
        speakingId = null
        tts.stop()
    }

    fun shutdown() {
        silence()
        tts.shutdown()
    }

    private fun finished(id: String?) {
        main.post {
            if (id == null || id != speakingId) return@post
            val done = onDone
            onDone = null
            speakingId = null
            done?.invoke()
        }
    }
}

/**
 * Remembers which sent messages were spoken, and for how long, so their bubbles can say so. Only a
 * hash of each message is kept, on this device.
 */
internal object SpokenMessages {
    private const val Limit = 200
    private const val Prefs = "spoken-messages"
    private const val EntriesKey = "entries"
    /** Oldest first, so trimming drops the oldest. */
    private val entries = LinkedHashMap<String, Long>()
    private var version by mutableIntStateOf(0)
    private var loaded = false

    /** Reading this subscribes the caller to new recordings. */
    fun duration(key: String): Long? {
        version
        return entries[key]
    }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        context.applicationContext
            .getSharedPreferences(Prefs, Context.MODE_PRIVATE)
            .getString(EntriesKey, null)
            ?.split(',')
            ?.forEach { entry ->
                val key = entry.substringBefore('=')
                entry.substringAfter('=', "").toLongOrNull()?.let { entries[key] = it }
            }
        version++
    }

    fun record(context: Context, text: String, durationMs: Long) {
        if (text.isBlank()) return
        load(context)
        val key = key(text)
        entries.remove(key)
        entries[key] = durationMs
        while (entries.size > Limit) entries.remove(entries.keys.first())
        context.applicationContext
            .getSharedPreferences(Prefs, Context.MODE_PRIVATE)
            .edit()
            .putString(EntriesKey, entries.entries.joinToString(",") { "${it.key}=${it.value}" })
            .apply()
        version++
    }

    fun key(text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
