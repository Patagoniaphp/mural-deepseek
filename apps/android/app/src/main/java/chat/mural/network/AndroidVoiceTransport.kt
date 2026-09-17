package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import chat.mural.R
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*

/**
 * Alternates Android recognition, DeepSeek text completion, and Android speech.
 * All state and Android speech calls are confined to the main thread. Each attempt
 * owns its callbacks, so late recognition/TTS results cannot revive a closed call.
 */
class AndroidVoiceTransport(context: Context, private val scope: CoroutineScope) {
    var onStarted: (() -> Unit)? = null
    var onTranscript: ((String, String, Int, Int) -> Unit)? = null
    var onFailure: ((Throwable) -> Unit)? = null
    var onLevels: ((Double, Double) -> Unit)? = null
    var onUsage: ((APIUsage) -> Unit)? = null
    var onClosed: ((Double) -> Unit)? = null
    var onBusy: ((Boolean) -> Unit)? = null
    var isBusy: Boolean = false; private set

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val history = VoiceTurnHistory()
    private val hints = ArrayDeque<String>()
    private var generation = 0L
    private var recognitionGeneration = 0L
    private var active = false
    private var muted = false
    private var inputPaused = false
    private var startedAt = 0L
    private var lastDuration = 0.0
    val elapsedSeconds: Double get() = if (active) (SystemClock.elapsedRealtime() - startedAt) / 1000.0 else lastDuration
    private var language = "en-US"
    private var instructions = ""
    private var api: APIClient? = null
    private var recognizer: SpeechRecognizer? = null
    private var recognitionRecovery: SpeechRecognitionRecovery? = null
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var requestJob: Job? = null
    private var listenJob: Job? = null
    private var speechTimeout: Job? = null
    private var lastUtterance: String? = null
    private var utterancePrefix: String? = null

    suspend fun connect(client: APIClient, policy: String, locale: String) = withContext(Dispatchers.Main.immediate) {
        disconnect()
        lastDuration = 0.0
        val token = generation
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw failure(R.string.error_transport_microphone)
        }
        recognitionRecovery = SpeechRecognitionRecovery(
            onDeviceAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app),
            systemAvailable = SpeechRecognizer.isRecognitionAvailable(app),
        )
        if (recognitionRecovery?.backend == null) throw recognitionFailure()
        active = true
        startedAt = SystemClock.elapsedRealtime()
        api = client; instructions = policy; language = locale
        try {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({ change ->
                    if (current(token) && change < 0) fail(failure(R.string.error_transport_audio_stopped))
                }, handler).build()
            focus = request
            if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw failure(R.string.error_transport_audio_stopped)
            }
            val ready = CompletableDeferred<Int>()
            tts = TextToSpeech(app) { status -> ready.complete(status) }
            if (withTimeout(15_000) { ready.await() } != TextToSpeech.SUCCESS) {
                throw failure(R.string.error_android_speech)
            }
            ensureActive()
            if (!current(token)) throw CancellationException("Voice attempt superseded")
            val engine = tts ?: throw failure(R.string.error_android_speech)
            if (engine.setLanguage(Locale.forLanguageTag(locale)) < TextToSpeech.LANG_AVAILABLE) {
                throw failure(R.string.error_android_speech_language)
            }
            engine.setAudioAttributes(attributes)
            engine.setSpeechRate(0.85f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { handler.post {
                    if (current(token) && id == lastUtterance) {
                        lastUtterance = null; speechTimeout?.cancel()
                        onLevels?.invoke(0.0, 0.0); busy(false); scheduleListening()
                    }
                } }
                @Deprecated("Android callback") override fun onError(id: String?) { handler.post {
                    if (current(token) && id?.startsWith("$utterancePrefix-") == true && lastUtterance != null)
                        fail(failure(R.string.error_android_speech))
                } }
            })
            onStarted?.invoke()
            if (current(token)) requestReply("Please begin the conversation with a brief greeting and one question.", false)
        } catch (error: Throwable) {
            if (token == generation) disconnect()
            throw error
        }
    }

    fun appendInstruction(text: String) {
        hints.addLast(text.take(2_000))
        while (hints.size > 8) hints.removeFirst()
    }

    /** Typed replies also enter the voice history; they must not create a second user transcript. */
    fun appendTyped(text: String) = history.append("user", text)

    fun checkIn(instruction: String) {
        if (!active || muted || inputPaused || isBusy) return
        appendInstruction(instruction)
        requestReply("Please check in briefly.", false)
    }

    fun pauseInput(value: Boolean) {
        inputPaused = value
        if (value) stopRecognition() else scheduleListening()
    }

    fun mute(value: Boolean) {
        muted = value
        if (value) stopRecognition() else scheduleListening()
    }

    fun speakReply(text: String) {
        if (!active) return
        stopRecognition()
        history.append("assistant", text)
        transcript("assistant", text)
        busy(true)
        val token = generation
        val chunks = text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1))
        val prefix = UUID.randomUUID().toString()
        utterancePrefix = prefix
        lastUtterance = "$prefix-${chunks.lastIndex}"
        onLevels?.invoke(0.0, 0.25)
        chunks.forEachIndexed { index, chunk ->
            if (tts?.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null, "$prefix-$index") != TextToSpeech.SUCCESS) {
                fail(failure(R.string.error_android_speech)); return
            }
        }
        speechTimeout?.cancel()
        speechTimeout = scope.launch {
            delay(120_000)
            if (current(token) && lastUtterance != null) fail(failure(R.string.error_android_speech))
        }
    }

    private fun requestReply(text: String, recordUser: Boolean) {
        if (!active || isBusy) return
        stopRecognition()
        val token = generation
        history.append("user", text)
        if (recordUser) transcript("user", text)
        busy(true)
        requestJob = scope.launch {
            try {
                val policy = instructions + "\n" + hints.joinToString("\n") +
                    "\nUse short speakable replies. You have no web search or real-time information. Never claim to browse."
                val result = api!!.converse(policy, history.messages())
                if (!current(token)) return@launch
                onUsage?.invoke(result.usage)
                speakReply(result.text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current(token)) fail(error) }
        }
    }

    private fun scheduleListening(delayMS: Long = 400) {
        listenJob?.cancel()
        if (!active || muted || inputPaused || isBusy || recognizer != null) return
        val token = generation
        listenJob = scope.launch {
            delay(delayMS) // Let speaker output settle before opening the microphone.
            if (current(token) && !muted && !inputPaused && !isBusy && recognizer == null) listen(token)
        }
    }

    private fun listen(token: Long) {
        val recognition = ++recognitionGeneration
        fun valid() = current(token) && recognition == recognitionGeneration && !muted && !inputPaused
        try {
            // Keep using the system service after a local model/service failure.
            val engine = if (Build.VERSION.SDK_INT >= 31 && recognitionRecovery?.backend == SpeechRecognitionRecovery.Backend.ON_DEVICE)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() { if (valid()) onLevels?.invoke(0.1, 0.0) }
                override fun onRmsChanged(rmsdB: Float) { if (valid()) onLevels?.invoke(((rmsdB + 2) / 12.0).coerceIn(0.0, 1.0), 0.0) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (valid()) onLevels?.invoke(0.0, 0.0) }
                override fun onPartialResults(results: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onResults(results: Bundle?) {
                    if (!valid()) return
                    recognitionRecovery?.recognized()
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim()?.take(2_000).orEmpty()
                    stopRecognition()
                    if (text.isBlank()) scheduleListening() else requestReply(text, true)
                }
                override fun onError(error: Int) {
                    if (!valid()) return
                    recoverRecognition(error)
                }
            })
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (_: SecurityException) {
            if (valid()) recoverRecognition(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        } catch (_: Exception) {
            if (valid()) recoverRecognition(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun recoverRecognition(error: Int) {
        val retry = recognitionRecovery?.retryAfter(error) == true
        stopRecognition() // Invalidate callbacks before cancel/destroy can deliver stale errors.
        if (retry) scheduleListening(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_000 else 400)
        else fail(recognitionFailure(error))
    }

    private fun recognitionFailure(error: Int? = null): RecognitionException {
        val resource = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.error_transport_microphone
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> R.string.error_android_recognition_language
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.error_android_recognition_model
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.error_android_recognition_network
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> R.string.error_android_recognition_busy
            else -> R.string.error_android_recognition
        }
        return RecognitionException(app.getString(resource))
    }

    private fun stopRecognition() {
        ++recognitionGeneration
        listenJob?.cancel(); listenJob = null
        val old = recognizer; recognizer = null
        runCatching { old?.cancel() }; runCatching { old?.destroy() }
        onLevels?.invoke(0.0, if (lastUtterance != null) 0.25 else 0.0)
    }
    private fun transcript(role: String, text: String) {
        val offset = (SystemClock.elapsedRealtime() - startedAt).coerceIn(0, Int.MAX_VALUE.toLong() - 1).toInt()
        onTranscript?.invoke(role, text, offset, offset + 1)
    }
    private fun current(token: Long) = active && generation == token
    private fun busy(value: Boolean) { isBusy = value; onBusy?.invoke(value) }
    private fun failure(id: Int) = TransportException(app.getString(id))
    private fun fail(error: Throwable) {
        if (!active) return
        disconnect()
        onFailure?.invoke(error)
    }
    fun close() {
        if (!active) return
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        disconnect(); onClosed?.invoke(seconds)
    }
    fun disconnect() {
        lastDuration = elapsedSeconds
        ++generation; active = false
        stopRecognition()
        recognitionRecovery = null
        requestJob?.cancel(); requestJob = null
        speechTimeout?.cancel(); speechTimeout = null
        lastUtterance = null
        runCatching { tts?.stop() }; runCatching { tts?.shutdown() }; tts = null
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null
        api = null; history.clear(); hints.clear(); muted = false; inputPaused = false
        onLevels?.invoke(0.0, 0.0); busy(false)
    }
    open class TransportException(message: String) : IOException(message)
    class RecognitionException(message: String) : TransportException(message)
}
