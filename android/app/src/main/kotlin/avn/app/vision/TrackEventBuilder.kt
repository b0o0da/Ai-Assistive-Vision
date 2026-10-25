package avn.app.vision

import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Side
import avn.contracts.Source

/**
 * architecture.md WS-06 / AVN-45: turns a stable [Track] into an [Event] for the engine, or null
 * if there is nothing new worth saying. Boxes only, no images (WS-06's "Watch out" -- this class
 * never sees a [Frame]/`Bitmap`, only [Track.lastBox] and plain pixel dimensions).
 *
 * **Type mapping.** `vehicle_approaching` here is a *class*-based mapping (any `vehicle`-labelled
 * track), not yet a growth-based approach signal: architecture.md's "Vehicle approach" behaviour
 * (box-area growth over a sliding window, a minimum track age, a higher threshold while walking)
 * is `ApproachDetector`'s job, a separate "Internal component" this ticket does not build --
 * AVN-45's own "Interfaces to use" lists only `Tracker.update`, `EventBuilder.build` and the
 * calibration JSON, not `ApproachDetector`. Until that ticket exists, every vehicle-labelled track
 * is reported as `vehicle_approaching` regardless of whether it is actually approaching; the real
 * approach/recede distinction is `ApproachDetector`'s to add later (it is in WS-06's "Internal
 * components" list and is one of the Backlog items this ticket unblocks).
 *
 * **Side and band.** Delegated to [SideEstimator] and [DistanceBander] (AVN-43) -- the only two
 * "Internal components" from architecture.md's WS-06 list this ticket already has available
 * (`ApproachDetector` and `MotionEstimator` are not built yet; `motion_state` and the approach
 * bonus they would feed the engine's risk formula are simply not available to this class).
 *
 * **Risk.** Always sent as 0. `Event.risk`'s own doc comment already says why: "Producers send 0;
 * the engine computes the real value" (`docs/api-contract.md`: "risk ... computed by the engine
 * formula (architecture §5)"). WS-06 does not own the risk formula, its weights, or
 * `engine_config.json` -- WS-16 (Marwan) does -- so duplicating that formula here would be a
 * second, driftable copy of logic this module has no business owning.
 *
 * **Emit only on change (ticket step 3 / "Done when": rate limited).** A track's *first* sighting
 * always emits. After that, [build] remembers the side and band it last *emitted* (not last seen)
 * for that track id, and emits again only if the side changed or the band escalated (moved
 * strictly nearer -- [DistanceBand]'s declared order is nearest-first, so "nearer" is a smaller
 * ordinal, the same comparison [DistanceBander.nearerOf] uses). A band moving *away* (far from
 * near) alone does not re-emit, matching the ticket's literal wording ("band escalation", not "any
 * band change"); architecture.md's own per-track rule names the same two triggers plus a third
 * ("lost for more than a reset time and returns") that is out of scope here -- the engine's
 * separate per-key cooldown (WS-16) is the backstop for that case, and for de-duplication in
 * general; this class's filter is a first-pass thinning, not the engine's only line of defence.
 *
 * Stateful (the per-track "last emitted" map) and not thread-safe, like AVN-40's tracker --
 * callers own one instance per camera session.
 */
class TrackEventBuilder(
    private val sideEstimator: SideEstimator = SideEstimator(),
    private val distanceBander: DistanceBander = DistanceBander()
) : EventBuilder {

    private data class Emitted(val side: Side, val band: DistanceBand)

    private val lastEmitted = mutableMapOf<Int, Emitted>()

    override fun build(track: Track, frameWidthPx: Float, frameHeightPx: Float): Event? {
        val box = track.lastBox
        val side = sideEstimator.estimate(box, frameWidthPx)
        val band = distanceBander.estimate(box, frameHeightPx)

        val previous = lastEmitted[track.id]
        val isNewTrack = previous == null
        val bandEscalated = previous != null && band.ordinal < previous.band.ordinal
        val sideChanged = previous != null && side != previous.side
        if (!isNewTrack && !bandEscalated && !sideChanged) return null

        lastEmitted[track.id] = Emitted(side, band)
        return Event(
            id = "evt_${track.id}_${box.timestampMs}",
            source = Source.CAMERA,
            type = eventTypeFor(track.label),
            label = track.label,
            side = side,
            distanceBand = band,
            confidence = box.confidence.toDouble(),
            risk = 0,
            timestamp = box.timestampMs
        )
    }

    private fun eventTypeFor(label: String): EventType = when (label) {
        "person" -> EventType.PERSON_CLOSE
        "vehicle" -> EventType.VEHICLE_APPROACHING
        else -> EventType.OBSTACLE_AHEAD
    }
}
