package avn.app.voice

/** Why a [SttEngine.listen] call returned null. Shared by the fake and the Android recognizer. */
enum class SttFailure { NONE, SILENCE, BUSY, NO_PERMISSION, UNAVAILABLE, NETWORK, AUDIO, OTHER }
