package avn.app.vision

/**
 * Lightweight filter for "plausibly a destination" -- reject pure noise before anything gets read
 * back to the user as a possible destination. Deliberately conservative/cheap: not a classifier,
 * just a guard against obviously-not-a-place-name OCR garbage (a single digit, a stray symbol, an
 * empty block). Anything that passes still goes through the confirmation step in
 * [OcrDestinationResolver] -- this filter only decides whether to *ask*, never whether to *route*.
 */
object DestinationCandidateFilter {
    private const val MIN_LENGTH = 3

    fun isPlausibleDestination(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_LENGTH) return false
        if (trimmed.none { it.isLetter() }) return false
        return true
    }
}

/**
 * Pure state machine for the "never fully automatic" rule: a sign read by OCR must be proposed,
 * then explicitly confirmed, before anything is sent to `/route`. Split out from
 * [OcrDestinationResolver]'s Android-side glue (speech via the engine, the actual network call)
 * for the same reason [avn.app.sos.SosCountdownState] is split from [avn.app.sos.RealSos] -- the
 * decision logic itself ("can a second candidate interrupt a pending one?", "how long before the
 * same sign can be asked about again?") is what's safety/UX-relevant and worth locking down with
 * tests that don't need a device.
 */
class OcrDestinationConfirmState(private val repromptCooldownSeconds: Double = 10.0) {
    enum class State { IDLE, PENDING_CONFIRMATION, CONFIRMED }

    var state: State = State.IDLE
        private set

    /** The candidate text currently awaiting (or just resolved from) confirmation. */
    var pendingText: String? = null
        private set

    private var lastResolvedText: String? = null
    private var lastResolvedAtSeconds: Double = Double.NEGATIVE_INFINITY

    /**
     * Returns true if this call actually started a new pending confirmation. A no-op (returns
     * false) if something is already pending/confirmed -- a second sign can't interrupt the one
     * already being asked about -- or if this exact text was just confirmed/dismissed within
     * [repromptCooldownSeconds] (so a sign that stays in frame doesn't get re-asked about every
     * OCR pass).
     */
    fun propose(candidateText: String, nowSeconds: Double): Boolean {
        if (state != State.IDLE) return false
        if (candidateText == lastResolvedText && nowSeconds - lastResolvedAtSeconds < repromptCooldownSeconds) {
            return false
        }
        state = State.PENDING_CONFIRMATION
        pendingText = candidateText
        return true
    }

    /** Returns the confirmed text, or null if nothing was pending. */
    fun confirm(nowSeconds: Double): String? {
        if (state != State.PENDING_CONFIRMATION) return null
        val text = pendingText
        state = State.CONFIRMED
        lastResolvedText = text
        lastResolvedAtSeconds = nowSeconds
        return text
    }

    /** Returns true if this call actually dismissed a pending confirmation. */
    fun dismiss(nowSeconds: Double): Boolean {
        if (state != State.PENDING_CONFIRMATION) return false
        lastResolvedText = pendingText
        lastResolvedAtSeconds = nowSeconds
        state = State.IDLE
        pendingText = null
        return true
    }

    /** Called once the caller has finished acting on a [confirm] (routing attempted, success or failure). */
    fun reset() {
        state = State.IDLE
        pendingText = null
    }
}
