package avn.app.core.consent

/** Result of asking the gate. [Denied] carries a short sentence the engine can speak. */
sealed interface ConsentCheck {
    data object Allowed : ConsentCheck

    data class Denied(val scope: ConsentScope, val spokenMessage: String) : ConsentCheck
}

/**
 * WS28-01: nothing starts the camera, microphone or screen reading, and nothing is sent to the cloud,
 * without the matching consent. The store is read on every call and never cached, so a revoke takes
 * effect at the next start or cloud call.
 */
class ConsentGuard(private val store: ConsentStore) {
    fun check(scope: ConsentScope): ConsentCheck =
        if (store.isGranted(scope)) ConsentCheck.Allowed else ConsentCheck.Denied(scope, deniedMessage(scope))

    /** Runs [action] only with consent. Without it, [action] never runs and [onDenied] decides the result. */
    inline fun <T> guard(scope: ConsentScope, onDenied: (ConsentCheck.Denied) -> T, action: () -> T): T =
        when (val result = check(scope)) {
            ConsentCheck.Allowed -> action()
            is ConsentCheck.Denied -> onDenied(result)
        }

    companion object {
        fun deniedMessage(scope: ConsentScope): String = when (scope) {
            ConsentScope.CAMERA -> "The camera is off because camera consent is not given."
            ConsentScope.MICROPHONE -> "Voice commands are off because microphone consent is not given."
            ConsentScope.SCREEN_READING -> "Screen reading is off because screen consent is not given."
            ConsentScope.CLOUD_FRAMES -> "I can't send pictures to the cloud without your consent."
            ConsentScope.CLOUD_SCREEN -> "I can't send screen content to the cloud without your consent."
            ConsentScope.FACES -> "Face recognition is off because face consent is not given."
        }
    }
}

/** Stand-in for tests and the Debug screen. Starts with nothing granted unless told otherwise. */
class FakeConsentStore(granted: Set<ConsentScope> = emptySet()) : ConsentStore {
    private val granted = granted.toMutableSet()

    fun grant(scope: ConsentScope) {
        granted += scope
    }

    fun revoke(scope: ConsentScope) {
        granted -= scope
    }

    override fun isGranted(scope: ConsentScope): Boolean = scope in granted
}
