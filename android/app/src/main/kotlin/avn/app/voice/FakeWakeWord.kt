package avn.app.voice

import avn.app.core.logging.JsonLinesLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val BUFFERED_WAKES = 16

/**
 * Stands in for the real wake word engine until TD-002 is decided. The Debug screen button and tests call [trigger].
 * Nothing is replayed to late subscribers, like a real detector. By default [trigger] works before [start], which is
 * how the Debug button behaved so far; set [emitOnlyWhileStarted] to exercise the voice flow strictly.
 */
@Singleton
class FakeWakeWord(
    private val log: VoiceLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val emitOnlyWhileStarted: Boolean = false
) : WakeWord {
    @Inject
    constructor(logger: JsonLinesLogger) : this(logger.asVoiceLog())

    private val mutable = MutableSharedFlow<WakeEvent>(
        extraBufferCapacity = BUFFERED_WAKES,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    var isRunning: Boolean = false
        private set

    override val wakes: Flow<WakeEvent> = mutable.asSharedFlow()

    override fun start() {
        isRunning = true
        log("wakeword", "fake_start", emptyMap())
    }

    override fun stop() {
        isRunning = false
        log("wakeword", "fake_stop", emptyMap())
    }

    /** Debug button and scripted tests. Returns false when the event was not emitted. */
    fun trigger(confidence: Float = 1f): Boolean {
        if (emitOnlyWhileStarted && !isRunning) {
            log("wakeword", "fake_trigger_ignored", mapOf("running" to false))
            return false
        }
        log("wakeword", "fake_trigger", emptyMap())
        return mutable.tryEmit(WakeEvent(clock(), confidence))
    }
}
