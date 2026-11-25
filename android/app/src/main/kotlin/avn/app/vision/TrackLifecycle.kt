package avn.app.vision

/**
 * Track identity rules (WS06-02), kept apart from the matching logic in [IouTracker]:
 * - IDs start at 1 and only ever increase; an ID is never reused, even after its track expired,
 *   so the engine's per-track rules (announce once, again only on escalation) cannot mix up two
 *   different objects.
 * - A lost track survives short gaps (missed detections, an occlusion) and expires when it has
 *   been unmatched for more than [maxLostFrames] frames **or** more than [maxLostMs] milliseconds,
 *   whichever comes first. The time limit matters when the camera drops to a low frame rate while
 *   the user stands still: eight frames can then span several seconds, long enough for the scene
 *   to change completely.
 * Both limits come from `config/tracker.json`.
 */
class TrackLifecycle(private val maxLostFrames: Int, private val maxLostMs: Long) {
    private var nextId = 1

    fun newId(): Int = nextId++

    fun isExpired(lostFrames: Int, lastSeenMs: Long, nowMs: Long): Boolean =
        lostFrames > maxLostFrames || nowMs - lastSeenMs > maxLostMs
}
