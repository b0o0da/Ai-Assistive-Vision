package avn.engine

import avn.contracts.SpeechRequest

/**
 * Engine-local test double for [SpeechOutput]: records what it was asked to speak/stop instead
 * of talking, exactly like `avn.app.voice.FakeSpeaker`.
 *
 * It is a separate class rather than a reuse of that one because `:engine` depends only on
 * `:contracts` (`android/CLAUDE.md`'s module layout: "`:engine` -- ... Depends only on
 * `:contracts`") while the real `FakeSpeaker` lives in `:app`, which depends on `:engine` --
 * reusing it here would mean `:engine` depending on `:app`, the wrong direction. This keeps the
 * same name and the same "record, don't speak" role so `InterruptRules`' tests read the way the
 * ticket asks ("Unit-test with FakeSpeaker") without inverting module boundaries.
 */
class FakeSpeaker : SpeechOutput {
    private val _spoken = mutableListOf<SpeechRequest>()
    val spoken: List<SpeechRequest> get() = _spoken

    private val _stopped = mutableListOf<String?>()
    val stopped: List<String?> get() = _stopped

    override val isSpeaking: Boolean
        get() = false

    override fun speak(request: SpeechRequest) {
        _spoken += request
    }

    override fun stop(utteranceId: String?) {
        _stopped += utteranceId
    }
}
