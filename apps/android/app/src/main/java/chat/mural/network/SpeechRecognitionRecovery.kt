package chat.mural.network

import android.speech.SpeechRecognizer

/** A local recognition service may exist without the requested language model. */
internal class SpeechRecognitionRecovery(onDeviceAvailable: Boolean, private val systemAvailable: Boolean) {
    enum class Backend { ON_DEVICE, SYSTEM }
    var backend: Backend? = when {
        onDeviceAvailable -> Backend.ON_DEVICE
        systemAvailable -> Backend.SYSTEM
        else -> null
    }; private set
    private var busyRetries = 0

    fun recognized() { busyRetries = 0 }

    /** Retain the fallback for the conversation instead of bouncing between services. */
    fun retryAfter(error: Int): Boolean {
        if (backend == null || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) return false
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            recognized()
            return true
        }
        if (backend == Backend.ON_DEVICE && systemAvailable && error in listOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        )) {
            backend = Backend.SYSTEM
            busyRetries = 0
            return true
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busyRetries < 2) {
            busyRetries++
            return true
        }
        return false
    }
}
