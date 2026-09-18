package chat.mural.network

import android.media.AudioManager.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioFocusTest {
    private class FakeLease(val callback: (Int) -> Unit, val result: Int) : PlaybackAudioFocus.Lease {
        var releases = 0
        var throwOnRequest = false
        override fun request(): Int {
            if (throwOnRequest) throw SecurityException("Platform rejected playback")
            return result
        }
        override fun release() {
            releases++
            // Some services deliver a late callback during or after abandonment.
            callback(AUDIOFOCUS_LOSS)
        }
    }
    private class Fixture(var result: Int = AUDIOFOCUS_REQUEST_GRANTED) {
        val leases = mutableListOf<FakeLease>()
        val events = mutableListOf<String>()
        var requestThrows = false
        val focus = PlaybackAudioFocus(
            createLease = { callback -> FakeLease(callback, result).also {
                it.throwOnRequest = requestThrows
                leases += it
            } },
            onPlay = { events += "play" },
            onPause = { events += "pause" },
            onUnavailable = { events += it.name },
        )
        fun change(value: Int) = leases.last().callback(value)
    }

    @Test fun grantedPlaybackStartsOnceAndDuplicateGainDoesNotReplay() {
        val f = Fixture()
        f.focus.acquire(); f.change(AUDIOFOCUS_GAIN)
        assertEquals(listOf("play"), f.events)
        assertEquals(0, f.leases.single().releases)
    }

    @Test fun transientInterruptionPausesAndResumesWithoutFailure() {
        val f = Fixture()
        f.focus.acquire()
        f.change(AUDIOFOCUS_LOSS_TRANSIENT)
        f.change(AUDIOFOCUS_LOSS_TRANSIENT)
        f.change(AUDIOFOCUS_GAIN)
        assertEquals(listOf("play", "pause", "play"), f.events)
        assertEquals(0, f.leases.single().releases)
    }

    @Test fun speechPausesInsteadOfDuckingOverAnotherAudioSource() {
        val f = Fixture()
        f.focus.acquire(); f.change(AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK); f.change(AUDIOFOCUS_GAIN)
        assertEquals(listOf("play", "pause", "play"), f.events)
    }

    @Test fun delayedGrantDoesNotPlayUntilFocusArrives() {
        val f = Fixture(AUDIOFOCUS_REQUEST_DELAYED)
        f.focus.acquire()
        assertEquals(listOf("pause"), f.events)
        f.change(AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "play"), f.events)
    }

    @Test fun deniedFocusReleasesTheRequestAndNeverPlays() {
        val f = Fixture(AUDIOFOCUS_REQUEST_FAILED)
        f.focus.acquire(); f.change(AUDIOFOCUS_GAIN)
        assertEquals(listOf("DENIED"), f.events)
        assertEquals(1, f.leases.single().releases)
    }

    @Test fun permanentLossReleasesAndDoesNotResumeFromALateGain() {
        val f = Fixture()
        f.focus.acquire(); f.change(AUDIOFOCUS_LOSS); f.change(AUDIOFOCUS_GAIN)
        assertEquals(listOf("play", "LOST"), f.events)
        assertEquals(1, f.leases.single().releases)
    }

    @Test fun delayedOrInterruptedPlaybackHasOneBoundedTimeout() {
        for (result in listOf(AUDIOFOCUS_REQUEST_GRANTED, AUDIOFOCUS_REQUEST_DELAYED)) {
            val f = Fixture(result)
            f.focus.acquire()
            if (result == AUDIOFOCUS_REQUEST_GRANTED) f.change(AUDIOFOCUS_LOSS_TRANSIENT)
            f.focus.expireWait(); f.focus.expireWait(); f.change(AUDIOFOCUS_GAIN)
            assertEquals(1, f.events.count { it == "TIMED_OUT" })
            assertEquals("TIMED_OUT", f.events.last())
            assertEquals(1, f.leases.single().releases)
        }
    }

    @Test fun closingDuringInterruptionIgnoresLaterGainAndTimeout() {
        val f = Fixture()
        f.focus.acquire(); f.change(AUDIOFOCUS_LOSS_TRANSIENT)
        f.focus.release(); f.change(AUDIOFOCUS_GAIN); f.focus.expireWait()
        assertEquals(listOf("play", "pause"), f.events)
        assertEquals(1, f.leases.single().releases)
    }

    @Test fun previousReplyCallbacksCannotInterruptTheNextReply() {
        val f = Fixture()
        f.focus.acquire()
        val first = f.leases.single()
        f.focus.release() // Speech completed; the recognizer now owns its own audio.
        first.callback(AUDIOFOCUS_LOSS_TRANSIENT)
        f.focus.acquire()
        first.callback(AUDIOFOCUS_LOSS); first.callback(AUDIOFOCUS_GAIN)
        assertEquals(listOf("play", "play"), f.events)
        assertEquals(1, first.releases)
        assertEquals(0, f.leases.last().releases)
    }

    @Test fun reacquiringReleasesTheOldLeaseBeforeUsingANewOne() {
        val f = Fixture()
        f.focus.acquire(); f.focus.acquire(); f.focus.release(); f.focus.release()
        assertEquals(listOf(1, 1), f.leases.map { it.releases })
        assertEquals(listOf("play", "play"), f.events)
    }

    @Test fun platformRequestExceptionIsRecoverableAndReleasesItsLease() {
        val f = Fixture().apply { requestThrows = true }
        f.focus.acquire()
        assertEquals(listOf("DENIED"), f.events)
        assertEquals(1, f.leases.single().releases)
    }

    @Test fun recoveredPlaybackIsNotStoppedByAnExpiredOldWait() {
        val f = Fixture()
        f.focus.acquire(); f.change(AUDIOFOCUS_LOSS_TRANSIENT); f.change(AUDIOFOCUS_GAIN)
        f.focus.expireWait()
        assertEquals(listOf("play", "pause", "play"), f.events)
        assertEquals(0, f.leases.single().releases)
    }
}
