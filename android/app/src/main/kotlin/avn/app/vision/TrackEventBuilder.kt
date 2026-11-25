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
 * **Type mapping.** `vehicle_approaching` is emitted only when the tracker has observed sustained
 * area growth and marked the track as approaching. A non-approaching vehicle can still produce a
 * generic obstacle warning when it enters the medium/near band; distant vehicles stay quiet.
 *
 * **Side and band.** Delegated to [SideEstimator] and [DistanceBander] (AVN-43). `ApproachDetector`
 * and `MotionEstimator` are built too, but inside [IouTracker] -- this class only sees their
 * result as [Track.approaching]; `motion_state` itself, and any approach bonus it could feed the
 * engine's risk formula, are not plumbed through to this class or the `Event` schema.
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

    private data class Emitted(val side: Side, val band: DistanceBand, val type: EventType)

    private val lastEmitted = mutableMapOf<Int, Emitted>()

    override fun build(track: Track, frameWidthPx: Float, frameHeightPx: Float): Event? {
        val box = track.lastBox
        val side = sideEstimator.estimate(box, frameWidthPx)
        val band = distanceBander.estimate(box, frameHeightPx)
        val eventType = eventTypeFor(track, band) ?: return null

        val previous = lastEmitted[track.id]
        val isNewTrack = previous == null
        val bandEscalated = previous != null && band.ordinal < previous.band.ordinal
        val sideChanged = previous != null && side != previous.side
        val typeChanged = previous != null && eventType != previous.type
        if (!isNewTrack && !bandEscalated && !sideChanged && !typeChanged) return null

        lastEmitted[track.id] = Emitted(side, band, eventType)
        return Event(
            id = "evt_${track.id}_${box.timestampMs}",
            source = Source.CAMERA,
            type = eventType,
            label = track.label,
            side = side,
            distanceBand = band,
            confidence = box.confidence.toDouble(),
            risk = 0,
            timestamp = box.timestampMs
        )
    }

    private fun eventTypeFor(track: Track, band: DistanceBand): EventType? = when (track.label.lowercase()) {
        "person" -> EventType.PERSON_CLOSE
        "vehicle", "car", "truck", "bus", "motorcycle", "motorbike", "bicycle" -> when {
            track.approaching -> EventType.VEHICLE_APPROACHING
            band != DistanceBand.FAR -> EventType.OBSTACLE_AHEAD
            else -> null
        }
        else -> EventType.OBSTACLE_AHEAD
    }
}
