package avn.app.core

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.Event
import avn.contracts.SpeechCandidate
import avn.engine.Clock
import avn.engine.DecisionRecord
import avn.engine.EngineInput
import avn.engine.PriorityEngine
import avn.engine.SpeechOutput
import avn.engine.SystemClock
import avn.engine.scenario.DecisionLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's [EngineInput]: engine v1 ([PriorityEngine], AVN-52) behind the one seam every producer
 * already uses. It replaces the Phase 2 stand-in `FakeEngineInput`, which only logged events.
 *
 * - Thread safety: [PriorityEngine] is plain single-threaded Kotlin, and producers (UI, camera,
 *   voice, navigation) call from different threads, so every call goes through one lock.
 * - Utterance completion: [SpeechOutput] has no completion callback yet. While the bound speaker
 *   reports `isSpeaking = false` right after `speak()` (true for `FakeSpeaker`), the utterance is
 *   treated as finished at once, which also lets the next queued item play. A real TTS speaker
 *   (the "Connect real TTS" task) calls [onUtteranceFinished] from its own callback instead.
 * - Decisions: every [DecisionRecord] goes to the JSON-lines log (ids, decision and reason only,
 *   never text) and to [decisions] for the Alert log screen. Persisting them to Room is the
 *   alert-log store task (WS03-05); this class keeps them in memory only.
 */
@Singleton
class AppPriorityEngine internal constructor(private val speaker: SpeechOutput, log: JsonLinesLogger, clock: Clock) :
    EngineInput {
    @Inject constructor(speaker: SpeechOutput, log: JsonLinesLogger) : this(speaker, log, SystemClock)

    private val lock = Any()
    private val engine = PriorityEngine(
        clock = clock,
        speaker = speaker,
        log = DecisionLog { id, decision, reason ->
            log.log("engine", "decision", fields = mapOf("id" to id, "decision" to decision, "reason" to reason))
        }
    )
    private val mutableDecisions = MutableStateFlow<List<DecisionRecord>>(emptyList())

    /** Engine decisions, oldest first, bounded by `decisionLogSize` in `config/engine.json`. */
    val decisions: StateFlow<List<DecisionRecord>> = mutableDecisions.asStateFlow()

    override fun submit(event: Event) = synchronized(lock) {
        engine.submit(event)
        settle()
    }

    override fun submit(candidate: SpeechCandidate) = synchronized(lock) {
        engine.submit(candidate)
        settle()
    }

    override fun updateContext(
        listening: Boolean,
        speaking: Boolean,
        moving: Boolean,
        navigationActive: Boolean,
        offline: Boolean,
        sosActive: Boolean
    ) = synchronized(lock) {
        engine.updateContext(listening, speaking, moving, navigationActive, offline, sosActive)
        settle()
    }

    /** Called by a real TTS speaker when an utterance ends or is stopped. */
    fun onUtteranceFinished(utteranceId: String) = synchronized(lock) {
        engine.onUtteranceFinished(utteranceId)
        settle()
    }

    private fun settle() {
        // A speaker that never reports speaking has already finished whatever it was handed.
        var guard = 0
        while (!speaker.isSpeaking && guard++ < MAX_INSTANT_FINISHES) {
            val current = engine.speakingNow ?: break
            engine.onUtteranceFinished(current.utteranceId)
        }
        mutableDecisions.value = engine.decisions
    }

    private companion object {
        /** More than the queue can ever hold; only guards against a speaker that misreports. */
        const val MAX_INSTANT_FINISHES = 16
    }
}
