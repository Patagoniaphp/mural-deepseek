package chat.mural.network

import android.speech.SpeechRecognizer.*
import chat.mural.network.SpeechRecognitionRecovery.Backend
import org.junit.Assert.*
import org.junit.Test

class SpeechRecognitionRecoveryTest {
    @Test fun missingLocalLanguageFallsBackAndRetainsTheSystemService() {
        val recovery = SpeechRecognitionRecovery(true, true)
        assertEquals(Backend.ON_DEVICE, recovery.backend)
        assertTrue(recovery.retryAfter(ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(Backend.SYSTEM, recovery.backend)
        recovery.recognized()
        assertTrue(recovery.retryAfter(ERROR_SPEECH_TIMEOUT))
        assertEquals(Backend.SYSTEM, recovery.backend)
    }

    @Test fun unsupportedLanguageFallsBackOnlyOnce() {
        val recovery = SpeechRecognitionRecovery(true, true)
        assertTrue(recovery.retryAfter(ERROR_LANGUAGE_NOT_SUPPORTED))
        assertFalse(recovery.retryAfter(ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(Backend.SYSTEM, recovery.backend)
    }

    @Test fun unavailableLocalServicesGetOneSystemAttempt() {
        for (error in listOf(ERROR_CLIENT, ERROR_SERVER_DISCONNECTED, ERROR_SERVER, ERROR_AUDIO)) {
            val recovery = SpeechRecognitionRecovery(true, true)
            assertTrue("Fallback for error $error", recovery.retryAfter(error))
            assertEquals(Backend.SYSTEM, recovery.backend)
            assertFalse("No loop for error $error", recovery.retryAfter(error))
        }
    }

    @Test fun missingLanguageWithoutSystemServiceCannotRetry() {
        val recovery = SpeechRecognitionRecovery(true, false)
        assertFalse(recovery.retryAfter(ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(Backend.ON_DEVICE, recovery.backend)
    }

    @Test fun devicesWithoutLocalRecognitionStartWithSystemService() {
        val recovery = SpeechRecognitionRecovery(false, true)
        assertEquals(Backend.SYSTEM, recovery.backend)
        assertFalse(recovery.retryAfter(ERROR_LANGUAGE_NOT_SUPPORTED))
    }

    @Test fun absentServicesCannotBeRetried() {
        val recovery = SpeechRecognitionRecovery(false, false)
        assertNull(recovery.backend)
        assertFalse(recovery.retryAfter(ERROR_RECOGNIZER_BUSY))
    }

    @Test fun permissionDenialsAndRateLimitsDoNotCauseRetriesOrFallbacks() {
        for (error in listOf(ERROR_INSUFFICIENT_PERMISSIONS, ERROR_TOO_MANY_REQUESTS)) {
            val recovery = SpeechRecognitionRecovery(true, true)
            assertFalse(recovery.retryAfter(error))
            assertEquals(Backend.ON_DEVICE, recovery.backend)
        }
    }

    @Test fun busyServiceRetriesAreBoundedAndResetAfterSuccessfulRecognition() {
        val recovery = SpeechRecognitionRecovery(false, true)
        assertTrue(recovery.retryAfter(ERROR_RECOGNIZER_BUSY))
        assertTrue(recovery.retryAfter(ERROR_RECOGNIZER_BUSY))
        assertFalse(recovery.retryAfter(ERROR_RECOGNIZER_BUSY))
        recovery.recognized()
        assertTrue(recovery.retryAfter(ERROR_RECOGNIZER_BUSY))
    }

    @Test fun SilenceDoesNotSwitchServicesAndNetworkFailureCannotLoop() {
        val recovery = SpeechRecognitionRecovery(true, true)
        assertTrue(recovery.retryAfter(ERROR_NO_MATCH))
        assertEquals(Backend.ON_DEVICE, recovery.backend)
        assertTrue(recovery.retryAfter(ERROR_NETWORK))
        assertEquals(Backend.SYSTEM, recovery.backend)
        assertFalse(recovery.retryAfter(ERROR_NETWORK_TIMEOUT))
    }
}
