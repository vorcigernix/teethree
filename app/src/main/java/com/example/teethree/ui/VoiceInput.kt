package com.example.teethree.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
 * mic keeps listening across pauses until release (push-to-talk). On Android 14+ both use Google's
 * speech service, the engine behind the keyboard's voice typing. Older phones hold-to-talk on the
 * on-device recognizer, which keeps one session (and so one sentence context) open through pauses.
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
    private var onDeviceRecognizer: SpeechRecognizer? = null
    /** The recognizer serving the current capture, so finish/cancel reach the right one. */
    private var current: SpeechRecognizer? = null
    /** On-device recognition failed (e.g. no language pack yet); use the online one for now. */
    private var onDeviceUnavailable = false
    private var onDeviceActive = false
    /** Push-to-talk records the mic itself and feeds the recognizer, so restarts lose no audio. */
    private var feeder: AudioFeeder? = null
    private var feederUnavailable = false
    private var holding = false
    /** The recognizer refused a segmented session once; push-to-talk falls back to restarts. */
    private var segmentedUnsupported = false
    private var segmentedActive = false
    private var finish = VoiceFinish.Send
    private var onDone: ((String, VoiceFinish) -> Unit)? = null

    private val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                        RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                }
            }
            // Leave room for a breath mid-sentence before "pause to send" kicks in.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            .putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1600L,
            )

    /**
     * Push-to-talk keeps listening through pauses until release. On Android 13+ that's one
     * segmented session (pauses only split it into segments). Otherwise the recognizer restarts
     * after each pause, with a long silence window so normal pauses don't end a session.
     */
    private val holdIntent: Intent
        get() =
            Intent(intent).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !segmentedUnsupported)
                    putExtra(
                        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    )
                else {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        10_000L,
                    )
                }
            }

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
        if (hold && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !feederUnavailable)
            feeder = AudioFeeder.open { level -> main.post { if (feeder != null) push(level) } }
        listen()
    }

    /** Stops capture; what was heard is delivered once the recognizer commits it. */
    fun finish(action: VoiceFinish = VoiceFinish.Send) {
        if (!listening) return
        finish = action
        holding = false
        // With fed audio, ending the stream is what makes the recognizer commit the last words.
        feeder?.endSession()
        current?.stopListening()
    }

    /** Discards the capture without delivering anything. */
    fun cancel() {
        onDone = null
        holding = false
        stopFeeder()
        if (listening) current?.cancel()
        listening = false
        transcript = ""
        partial = ""
    }

    fun dispose() {
        cancel()
        recognizer?.destroy()
        recognizer = null
        onDeviceRecognizer?.destroy()
        onDeviceRecognizer = null
        current = null
    }

    private fun createRecognizer(): SpeechRecognizer {
        val googleServicesComponent =
            android.content.ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
            )
        return try {
            SpeechRecognizer.createSpeechRecognizer(context, googleServicesComponent)
        } catch (_: Throwable) {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun listen() {
        // Android 14+: always Google's speech service, the engine the keyboard's voice typing uses.
        // Older phones hold-to-talk on the on-device recognizer, which keeps pauses in one session.
        onDeviceActive =
            holding &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !onDeviceUnavailable &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val active =
            if (onDeviceActive)
                onDeviceRecognizer
                    ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                        it.setRecognitionListener(listener)
                        onDeviceRecognizer = it
                    }
            else
                recognizer
                    ?: createRecognizer().also {
                        it.setRecognitionListener(listener)
                        recognizer = it
                    }
        current = active
        segmentedActive =
            holding && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !segmentedUnsupported
        val fed = feeder?.takeIf { holding }
        if (fed != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            active.startListening(
                Intent(holdIntent).apply {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fed.newSession())
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                    )
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioFeeder.Rate)
                }
            )
        else active.startListening(if (holding) holdIntent else intent)
    }

    /** Marks a capture that the system dictation dialog made, so it has no duration. */
    fun noteExternalCapture() {
        lastDurationMs = 0L
    }

    private fun stopFeeder() {
        feeder?.close()
        feeder = null
    }

    private fun deliver() {
        stopFeeder()
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

            override fun onRmsChanged(rmsdB: Float) {
                if (feeder == null) push((rmsdB + 2f) / 12f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            // The recognizer is closing this phrase: from here, buffer the mic for the next one.
            override fun onEndOfSpeech() {
                feeder?.endSession()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstResult()?.let { partial = it }
            }

            override fun onResults(results: Bundle?) {
                if (!listening) return
                feeder?.endSession()
                transcript = joinSpeech(transcript, results.firstResult() ?: partial)
                partial = ""
                if (holding) listen() else deliver()
            }

            // Segmented sessions (push-to-talk): each pause commits a segment, the session goes on.
            override fun onSegmentResults(segmentResults: Bundle) {
                if (!listening) return
                transcript = joinSpeech(transcript, segmentResults.firstResult() ?: partial)
                partial = ""
            }

            override fun onEndOfSegmentedSession() {
                if (!listening) return
                transcript = joinSpeech(transcript, partial)
                partial = ""
                // Ended by release (finish) or by the recognizer's own limit while still held.
                if (holding) listen() else deliver()
            }

            override fun onError(code: Int) {
                if (!listening) return
                feeder?.endSession()
                // The recognizer won't take fed audio: let it use the microphone itself.
                if (
                    feeder != null &&
                        holding &&
                        code in OnDeviceUnavailableErrors &&
                        code != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED &&
                        code != SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                ) {
                    feederUnavailable = true
                    stopFeeder()
                    listen()
                    return
                }
                // No on-device model for this language yet: fetch it for next time, and carry on
                // with the online recognizer for this hold.
                if (onDeviceActive && holding && code in OnDeviceUnavailableErrors) {
                    onDeviceUnavailable = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        runCatching { onDeviceRecognizer?.triggerModelDownload(intent) }
                    listen()
                    return
                }
                // A recognizer without segmented sessions: retry this hold the restart way.
                if (
                    segmentedActive &&
                        holding &&
                        code != SpeechRecognizer.ERROR_NO_MATCH &&
                        code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                        code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                        code != SpeechRecognizer.ERROR_NETWORK &&
                        code != SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                ) {
                    segmentedUnsupported = true
                    listen()
                    return
                }
                when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT ->
                        if (holding) {
                            // A session that ended without a final result still heard these words:
                            // keep them before starting the next, instead of starting over.
                            transcript = joinSpeech(transcript, partial)
                            partial = ""
                            listen()
                        } else deliver()
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

    private val main = Handler(Looper.getMainLooper())

    companion object {
        const val LevelCount = 24

        private val OnDeviceUnavailableErrors =
            setOf(
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_CLIENT,
            )
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

/**
 * The language to recognize. English set for a region without its own English speech model (e.g.
 * "en-CZ") recognizes as US English: those regional variants have no on-device pack and weaker
 * online accuracy. Other languages and English regions pass through unchanged.
 */
internal fun recognitionLanguage(locale: java.util.Locale = java.util.Locale.getDefault()): String =
    if (locale.language == "en" && locale.country !in EnglishSpeechRegions) "en-US"
    else locale.toLanguageTag()

private val EnglishSpeechRegions =
    setOf("US", "GB", "AU", "CA", "IN", "IE", "NZ", "ZA", "SG", "PH", "KE", "NG", "PK", "GH", "TZ")

/**
 * Records the microphone for a whole push-to-talk hold and streams it to one recognizer session
 * at a time through a pipe. Between sessions (the recognizer closes a phrase at each pause and is
 * restarted) the audio is buffered and handed to the next session first, so no word falls into
 * the restart gap.
 */
private class AudioFeeder private constructor(
    private val record: android.media.AudioRecord,
    private val onLevel: (Float) -> Unit,
) {
    private val lock = Any()
    private var sink: java.io.OutputStream? = null
    private var source: android.os.ParcelFileDescriptor? = null
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    @Volatile private var running = true

    private val reader =
        Thread({
            val chunk = ByteArray(ChunkBytes)
            while (running) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                val bytes = chunk.copyOf(read)
                onLevel(level(bytes))
                synchronized(lock) {
                    val out = sink
                    if (out != null)
                        try {
                            out.write(bytes)
                            return@synchronized
                        } catch (_: java.io.IOException) {
                            sink = null
                        }
                    pending.addLast(bytes)
                    pendingBytes += bytes.size
                    while (pendingBytes > MaxPendingBytes) pendingBytes -= pending.removeFirst().size
                }
            }
        }, "push-to-talk-audio")

    init {
        record.startRecording()
        reader.start()
    }

    /** A new stream for the next recognizer session, starting with what was buffered meanwhile. */
    fun newSession(): android.os.ParcelFileDescriptor {
        val (read, write) = android.os.ParcelFileDescriptor.createPipe()
        synchronized(lock) {
            endSessionLocked()
            val out = android.os.ParcelFileDescriptor.AutoCloseOutputStream(write)
            try {
                while (pending.isNotEmpty()) out.write(pending.removeFirst())
            } catch (_: java.io.IOException) {}
            pendingBytes = 0
            sink = out
            source = read
        }
        return read
    }

    /** Ends the current stream (the recognizer then commits it); new audio waits in the buffer. */
    fun endSession() = synchronized(lock) { endSessionLocked() }

    private fun endSessionLocked() {
        runCatching { sink?.close() }
        sink = null
        runCatching { source?.close() }
        source = null
    }

    fun close() {
        running = false
        runCatching { record.stop() }
        reader.join(200)
        record.release()
        endSession()
    }

    private fun level(bytes: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = kotlin.math.sqrt(sum / count)
        // Roughly matches the recognizer's own rmsdB scale used for the waveform.
        return ((20 * kotlin.math.log10(rms.coerceAtLeast(1.0)) - 30) / 40).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val Rate = 16_000
        private const val ChunkBytes = Rate / 50 * 2 // 20 ms of 16-bit mono
        private const val MaxPendingBytes = Rate * 2 * 10 // 10 s

        /** Null when the microphone can't be opened; push-to-talk then uses the recognizer's own. */
        @android.annotation.SuppressLint("MissingPermission") // The voice starter checks it first.
        fun open(onLevel: (Float) -> Unit): AudioFeeder? =
            // Fed audio (EXTRA_AUDIO_SOURCE) needs Android 13.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null
            else runCatching {
                    val minimum =
                        android.media.AudioRecord.getMinBufferSize(
                            Rate,
                            android.media.AudioFormat.CHANNEL_IN_MONO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT,
                        )
                    val record =
                        android.media.AudioRecord(
                            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            Rate,
                            android.media.AudioFormat.CHANNEL_IN_MONO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT,
                            maxOf(minimum, Rate * 2 / 5),
                        )
                    if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                        record.release()
                        null
                    } else AudioFeeder(record, onLevel)
                }
                .getOrNull()
    }
}
