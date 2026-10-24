package avn.app.vision

import avn.app.core.logging.JsonLinesLogger
import avn.app.navigation.Navigation
import avn.contracts.EventType
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns an OCR-read sign into a routed destination, but **never automatically** -- always speak
 * it back and wait for an explicit confirm first. Glue around the pure
 * [OcrDestinationConfirmState] decision logic, built entirely against seams ([Navigation],
 * [EngineInput]) rather than concrete classes, so it is unit-testable without a device, same as
 * [avn.app.sos.RealSos] around [avn.app.sos.SosCountdownState].
 *
 * Speaks only through [EngineInput.submit] (one-speaker rule, android/CLAUDE.md rule 2) --
 * never a direct TTS call. On a confirmed candidate this only calls [Navigation.start]; the first
 * spoken instruction on success already comes from [avn.app.navigation.RealNavigation] itself, so
 * this class only has to speak for the propose/dismiss/failure cases.
 *
 * Nothing in this codebase feeds live camera frames into [onTextDetected] yet -- wiring an
 * [avn.app.vision.OcrEngine] read loop into this is vision-pipeline/camera integration, out of
 * scope here the same way turn-by-turn re-narration was left for navigation-to-engine integration.
 */
@Singleton
class OcrDestinationResolver @Inject constructor(
    private val navigation: Navigation,
    private val engineInput: EngineInput,
    private val log: JsonLinesLogger
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val confirmState = OcrDestinationConfirmState()

    /** The candidate text currently awaiting confirmation, or null if nothing is pending. */
    val pendingCandidate: String? get() = confirmState.pendingText

    /**
     * Feed a single OCR read here (e.g. once per [avn.app.vision.OcrEngine.read] pass). A no-op
     * if the text doesn't pass [DestinationCandidateFilter], or if a confirmation is already
     * pending/in flight (see [OcrDestinationConfirmState.propose] for the exact rules).
     */
    fun onTextDetected(text: String, nowSeconds: Double = System.currentTimeMillis() / 1000.0) {
        if (!DestinationCandidateFilter.isPlausibleDestination(text)) return
        if (confirmState.propose(text, nowSeconds)) {
            log.log("ocr", "destination_proposed")
            speak("Did you mean navigate to $text? Confirm to go there, or dismiss to ignore.", Source.CAMERA, EventType.TEXT_RESULT)
        }
    }

    /** User explicitly confirmed the pending candidate -- fires `/route`. */
    fun confirm(nowSeconds: Double = System.currentTimeMillis() / 1000.0) {
        val text = confirmState.confirm(nowSeconds) ?: return
        scope.launch {
            try {
                val started = navigation.start(text)
                log.log("ocr", "destination_route_result", fields = mapOf("started" to started))
                if (!started) {
                    speak("I couldn't find a route to $text.", Source.NAVIGATION, EventType.SYSTEM_NOTICE)
                }
            } catch (e: Exception) {
                log.log("ocr", "destination_route_failed", fields = mapOf("error" to e.javaClass.simpleName))
                speak("I couldn't find a route to $text.", Source.NAVIGATION, EventType.SYSTEM_NOTICE)
            } finally {
                confirmState.reset()
            }
        }
    }

    /** User explicitly declined the pending candidate -- nothing is ever sent to `/route`. */
    fun dismiss(nowSeconds: Double = System.currentTimeMillis() / 1000.0) {
        if (confirmState.dismiss(nowSeconds)) {
            log.log("ocr", "destination_dismissed")
            speak("Okay, not navigating there.", Source.CAMERA, EventType.TEXT_RESULT)
        }
    }

    private fun speak(text: String, source: Source, type: EventType) {
        engineInput.submit(SpeechCandidate(text = text, source = source, type = type, timeSensitive = true))
    }
}
