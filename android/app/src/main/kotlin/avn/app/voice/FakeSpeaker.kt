package avn.app.voice

import avn.app.core.logging.JsonLinesLogger
import avn.contracts.Level
import avn.contracts.SpeechRequest
import avn.engine.SpeechOutput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val SHOWN_ON_DEBUG_SCREEN = 20
private const val MAX_RECORDS = 1_000

data class SpokenRecord(val request: SpeechRequest, val atMs: Long)

data class StopRecord(val utteranceId: String?, val atMs: Long)

enum class InterruptReason { STOPPED, PREEMPTED }

data class InterruptRecord(val utteranceId: String, val reason: InterruptReason, val atMs: Long)

/**
 * Records everything the engine asks the speaker to do, so the engine, intents and SOS can be tested without audio.
 * With [autoFinish] on (the default) every request ends at once and [isSpeaking] stays false. Turn it off to keep a
 * request "speaking" until [finishCurrent], [stop] or a Critical request, and to detect overlapping speech.
 */
@Singleton
class FakeSpeaker(
    private val log: VoiceLog,
    private val clock: () -> Long = System::currentTimeMillis,
    var autoFinish: Boolean = true
) : SpeechOutput {
    @Inject
    constructor(logger: JsonLinesLogger) : this(logger.asVoiceLog())

    private val lock = Any()
    private val spokenRecords = mutableListOf<SpokenRecord>()
    private val stopRecords = mutableListOf<StopRecord>()
    private val interruptRecords = mutableListOf<InterruptRecord>()
    private var current: SpeechRequest? = null
    private var overlapCount = 0
    private val shown = MutableStateFlow<List<SpeechRequest>>(emptyList())

    /** The last few requests, for the Debug screen. */
    val spoken: StateFlow<List<SpeechRequest>> = shown.asStateFlow()

    val requests: List<SpokenRecord> get() = synchronized(lock) { spokenRecords.toList() }

    val stops: List<StopRecord> get() = synchronized(lock) { stopRecords.toList() }

    val interruptions: List<InterruptRecord> get() = synchronized(lock) { interruptRecords.toList() }

    val spokenTexts: List<String> get() = requests.map { it.request.text }

    /** Requests that started while another, non-pre-empted request was still speaking. */
    val overlaps: Int get() = synchronized(lock) { overlapCount }

    val currentUtteranceId: String? get() = synchronized(lock) { current?.utteranceId }

    override val isSpeaking: Boolean get() = synchronized(lock) { current != null }

    override fun speak(request: SpeechRequest) {
        log(
            "speaker",
            "fake_speak",
            mapOf("utterance" to request.utteranceId, "level" to request.level, "chars" to request.text.length)
        )
        synchronized(lock) {
            spokenRecords.add(SpokenRecord(request, clock()))
            if (spokenRecords.size > MAX_RECORDS) spokenRecords.removeAt(0)
            val playing = current
            if (playing != null) {
                if (request.level == Level.CRITICAL && playing.level != Level.CRITICAL) {
                    interrupt(playing, InterruptReason.PREEMPTED)
                } else {
                    overlapCount++
                }
            }
            current = if (autoFinish) null else request
        }
        shown.update { (it + request).takeLast(SHOWN_ON_DEBUG_SCREEN) }
    }

    /** `stop(null)` is a flush: whatever is speaking is cut. */
    override fun stop(utteranceId: String?) {
        log("speaker", "fake_stop", mapOf("utterance" to utteranceId))
        synchronized(lock) {
            stopRecords.add(StopRecord(utteranceId, clock()))
            val playing = current
            if (playing != null && (utteranceId == null || utteranceId == playing.utteranceId)) {
                interrupt(playing, InterruptReason.STOPPED)
                current = null
            }
        }
    }

    fun finishCurrent() {
        synchronized(lock) { current = null }
    }

    fun reset() {
        synchronized(lock) {
            spokenRecords.clear()
            stopRecords.clear()
            interruptRecords.clear()
            current = null
            overlapCount = 0
        }
        shown.value = emptyList()
    }

    fun assertSpoke(text: String, level: Level? = null) {
        val found = requests.any { it.request.text == text && (level == null || it.request.level == level) }
        if (!found) fail("expected to speak \"$text\"${level?.let { " at $it" }.orEmpty()}, spoke ${describe()}")
    }

    fun assertSpokeContaining(fragment: String) {
        if (spokenTexts.none { it.contains(fragment, ignoreCase = true) }) {
            fail("expected a request containing \"$fragment\", spoke ${describe()}")
        }
    }

    /** The texts must appear in this order. Other requests may sit between them. */
    fun assertSpokeInOrder(vararg texts: String) {
        var next = 0
        for (spoken in spokenTexts) {
            if (next < texts.size && spoken == texts[next]) next++
        }
        if (next < texts.size) fail("expected ${texts.toList()} in order, spoke ${describe()}")
    }

    fun assertNothingSpoken() {
        if (requests.isNotEmpty()) fail("expected silence, spoke ${describe()}")
    }

    fun assertInterrupted(utteranceId: String) {
        if (interruptions.none { it.utteranceId == utteranceId }) {
            val seen = interruptions.map { it.utteranceId }
            fail("expected \"$utteranceId\" to be interrupted, interruptions were $seen")
        }
    }

    fun assertNotInterrupted(utteranceId: String) {
        if (interruptions.any { it.utteranceId == utteranceId }) fail("\"$utteranceId\" was interrupted")
    }

    fun assertFlushed() {
        if (stops.none { it.utteranceId == null }) {
            fail("expected stop(null), stop calls were ${stops.map { it.utteranceId }}")
        }
    }

    fun assertNoOverlap() {
        if (overlaps > 0) fail("speech overlapped $overlaps time(s)")
    }

    private fun interrupt(request: SpeechRequest, reason: InterruptReason) {
        interruptRecords.add(InterruptRecord(request.utteranceId, reason, clock()))
    }

    private fun describe(): String =
        requests.joinToString(prefix = "[", postfix = "]") { "\"${it.request.text}\" (${it.request.level})" }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
