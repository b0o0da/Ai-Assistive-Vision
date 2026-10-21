package avn.engine

import avn.contracts.SpeechRequest

/** Called by the engine only. Utterance callbacks are added with the engine in Phase 3. */
interface SpeechOutput {
    val isSpeaking: Boolean

    fun speak(request: SpeechRequest)

    fun stop(utteranceId: String? = null)
}
