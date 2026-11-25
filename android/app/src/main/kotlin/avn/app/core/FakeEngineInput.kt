package avn.app.core

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Level
import avn.contracts.SpeechCandidate
import avn.contracts.SpeechRequest
import avn.engine.Decision
import avn.engine.EngineInput
import avn.engine.InterruptRules
import avn.engine.SpeechItem
import avn.engine.SpeechOutput
import avn.engine.SystemClock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stand-in until the real engine lands in Phase 3: logs events and runs every candidate through
 * [InterruptRules] (AVN-55) before the speaker, so Critical/Warning/Info interruption behaviour
 * works even before risk scoring, thresholds and cooldowns (the other WS-16 tables) exist.
 *
 * [SpeechOutput] has no utterance-completion callback yet (its own doc comment defers that to
 * "Phase 3"), so this class treats [SpeechOutput.isSpeaking] being false right after a `speak()`
 * call as the gap opening immediately -- true for `avn.app.voice.FakeSpeaker`, which always
 * reports `isSpeaking = false`. A real TTS speaker will need to call
 * `InterruptRules.notifyFinished` from its own utterance callback instead; that wiring belongs
 * to whichever ticket replaces this stand-in with the real engine.
 */
@Singleton
class FakeEngineInput @Inject constructor(private val speaker: SpeechOutput, private val log: JsonLinesLogger) :
    EngineInput {
    private val counter = AtomicLong()
    private val interruptRules = InterruptRules(speaker, SystemClock)

    override fun submit(event: Event) {
        val fields = mapOf(
            "id" to event.id,
            "source" to event.source,
            "type" to event.type,
            "label" to event.label,
            "side" to event.side,
            "band" to event.distanceBand,
            "confidence" to event.confidence
        )
        log.log("engine", "fake_event", fields = fields)
    }

    override fun submit(candidate: SpeechCandidate) {
        val request = SpeechRequest(
            utteranceId = "utt_${counter.incrementAndGet()}",
            text = candidate.text,
            level = Level.INFO,
            source = candidate.source,
            interruptible = true,
            eventId = candidate.eventId
        )
        val item = SpeechItem(
            request = request,
            userRequested = candidate.userRequested,
            sosCountdown = candidate.type == EventType.SOS_COUNTDOWN
        )
        val decision = interruptRules.submit(item)
        if (decision == Decision.SPOKEN && !speaker.isSpeaking) {
            interruptRules.notifyFinished(request.utteranceId)
        }
    }

    override fun updateContext(
        listening: Boolean,
        speaking: Boolean,
        moving: Boolean,
        navigationActive: Boolean,
        offline: Boolean,
        sosActive: Boolean
    ) {
        interruptRules.updateContext(listening, speaking, moving, navigationActive, offline, sosActive)
    }
}
