package chat.mural.network

import android.media.AudioManager

/** One focus lease per spoken reply. Released leases cannot affect later turns. Main thread only. */
internal class PlaybackAudioFocus(
    private val createLease: ((Int) -> Unit) -> Lease,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onUnavailable: (Failure) -> Unit,
) {
    interface Lease {
        fun request(): Int
        fun release()
    }
    enum class Failure { DENIED, LOST, TIMED_OUT }
    private enum class State { IDLE, REQUESTING, PLAYING, WAITING }
    private var state = State.IDLE
    private var lease: Lease? = null
    private var generation = 0L

    fun acquire() {
        release()
        val token = generation
        state = State.REQUESTING
        try {
            val next = createLease { change ->
                if (token == generation && state != State.IDLE) changed(change)
            }
            lease = next
            val result = next.request()
            if (token != generation || state != State.REQUESTING) return
            when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> play()
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> pause()
                else -> unavailable(Failure.DENIED)
            }
        } catch (_: Exception) {
            if (token == generation) unavailable(Failure.DENIED)
        }
    }

    private fun changed(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> play()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
            AudioManager.AUDIOFOCUS_LOSS -> unavailable(Failure.LOST)
        }
    }

    private fun play() {
        if (state !in listOf(State.REQUESTING, State.WAITING)) return
        state = State.PLAYING
        onPlay()
    }

    private fun pause() {
        if (state == State.WAITING) return
        state = State.WAITING
        onPause()
    }

    /** The caller bounds a wait for focus; duplicate loss callbacks do not extend that wait. */
    fun expireWait() {
        if (state == State.WAITING) unavailable(Failure.TIMED_OUT)
    }

    private fun unavailable(reason: Failure) {
        release()
        onUnavailable(reason)
    }

    fun release() {
        ++generation
        state = State.IDLE
        val previous = lease
        lease = null
        runCatching { previous?.release() }
    }
}
