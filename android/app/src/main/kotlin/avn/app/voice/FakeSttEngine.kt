package avn.app.voice

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.Transcript
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

private const val BUFFERED_PARTIALS = 16

/** One scripted outcome of a single [SttEngine.listen] call. */
sealed class SttScript {
    /** Speech: [partials] stream out first, then the final text. [delayMs] is spread across them. */
    data class Say(
        val text: String,
        val confidence: Double = 1.0,
        val partials: List<String> = emptyList(),
        val delayMs: Long = 0L,
        val language: String = "en"
    ) : SttScript()

    data object Silence : SttScript()

    data class Fail(val failure: SttFailure) : SttScript()

    /** Never produces a result, so the call ends with the caller's timeout. */
    data object Hang : SttScript()
}

/**
 * Scripted speech recognition for the voice flow and the intent router. Each [listen] consumes the next scripted
 * step. With nothing scripted it waits for [inject], which is what the Debug text box uses, until the timeout.
 */
@Singleton
class FakeSttEngine(private val log: VoiceLog) : SttEngine {
    @Inject
    constructor(logger: JsonLinesLogger) : this(logger.asVoiceLog())

    private val lock = Any()
    private val queue = ArrayDeque<SttScript>()
    private val arrived = Channel<Unit>(Channel.CONFLATED)
    private val partialFlow = MutableSharedFlow<String>(extraBufferCapacity = BUFFERED_PARTIALS)
    private val listens = AtomicInteger()

    /** Partial hypotheses of the call in progress. Not replayed to late subscribers. */
    val partials: Flow<String> = partialFlow.asSharedFlow()

    @Volatile
    var lastFailure: SttFailure = SttFailure.NONE
        private set

    val listenCount: Int get() = listens.get()

    val pending: Int get() = synchronized(lock) { queue.size }

    fun enqueue(vararg steps: SttScript) {
        log("stt", "fake_script", mapOf("steps" to steps.size))
        add(steps.toList())
    }

    /** Debug text box. Queues one transcript for the next [listen]. */
    fun inject(text: String) {
        log("stt", "fake_inject", mapOf("chars" to text.length))
        add(listOf(SttScript.Say(text)))
    }

    fun clearScript() {
        synchronized(lock) { queue.clear() }
    }

    override suspend fun listen(timeoutMs: Long): Transcript? {
        lastFailure = SttFailure.NONE
        listens.incrementAndGet()
        val transcript = withTimeoutOrNull(timeoutMs) { play(awaitStep()) }
        if (transcript == null && lastFailure == SttFailure.NONE) lastFailure = SttFailure.SILENCE
        return transcript
    }

    private fun add(steps: List<SttScript>) {
        synchronized(lock) { queue.addAll(steps) }
        arrived.trySend(Unit)
    }

    private suspend fun awaitStep(): SttScript {
        while (true) {
            val step = synchronized(lock) { queue.removeFirstOrNull() }
            if (step != null) return step
            arrived.receive()
        }
    }

    private suspend fun play(step: SttScript): Transcript? = when (step) {
        is SttScript.Say -> say(step)
        SttScript.Silence -> {
            lastFailure = SttFailure.SILENCE
            null
        }
        is SttScript.Fail -> {
            lastFailure = step.failure
            null
        }
        SttScript.Hang -> awaitCancellation()
    }

    private suspend fun say(step: SttScript.Say): Transcript {
        log("stt", "fake_listen", mapOf("partials" to step.partials.size, "chars" to step.text.length))
        val pause = step.delayMs / (step.partials.size + 1)
        for (partial in step.partials) {
            partialFlow.emit(partial)
            if (pause > 0) delay(pause)
        }
        if (pause > 0) delay(pause)
        return Transcript(step.text, step.confidence, step.language)
    }
}
