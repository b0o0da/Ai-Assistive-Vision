package avn.app.vision

import avn.app.perf.PerfLogger

/** A light, on-device ByteTrack-style tracker: high-confidence matching first, then low-confidence
 * detections may recover an existing track but can never create a new one. Boxes and short history
 * remain in memory; the tracker never receives or stores image pixels.
 */
class IouTracker(
    private val config: TrackerConfig = TrackerConfig.load(),
    private val isUserMoving: () -> Boolean = { false },
    private val perf: PerfLogger? = null
) : Tracker {
    private data class State(
        val id: Int,
        val label: String,
        var boxes: List<Detection>,
        var age: Int,
        var lostFrames: Int
    )

    private val tracks = linkedMapOf<Int, State>()
    private val approachDetector = ApproachDetector(config)
    private val lifecycle = TrackLifecycle(config.maxLostFrames, config.maxLostMs)

    override fun update(detections: List<Detection>, timestampMs: Long): List<Track> =
        perf?.measure("tracker", "update_ms") { updateInternal(detections, timestampMs) }
            ?: updateInternal(detections, timestampMs)

    private fun updateInternal(detections: List<Detection>, timestampMs: Long): List<Track> {
        tracks.values.forEach { it.lostFrames++ }
        tracks.entries.removeAll { (_, s) ->
            lifecycle.isExpired(s.lostFrames, s.boxes.last().timestampMs, timestampMs)
        }

        val valid = detections.filter {
            it.w > 0f && it.h > 0f && it.confidence.isFinite() && it.confidence >= config.lowConfidence
        }
        val high = valid.filter { it.confidence >= config.highConfidence }
        val low = valid.filter { it.confidence < config.highConfidence }
        val unmatchedTrackIds = tracks.keys.toMutableSet()
        val visible = mutableListOf<State>()
        val matchedHighIndices = mutableSetOf<Int>()

        associate(high, timestampMs, unmatchedTrackIds, visible, matchedHighIndices)
        // ByteTrack's second association uses low-score boxes only for tracks that remain unmatched.
        associate(low, timestampMs, unmatchedTrackIds, visible, mutableSetOf())

        high.forEachIndexed { index, detection ->
            if (index !in matchedHighIndices) {
                val observed = detection.copy(timestampMs = timestampMs)
                val state = State(lifecycle.newId(), observed.label, listOf(observed), age = 1, lostFrames = 0)
                tracks[state.id] = state
                visible += state
            }
        }

        return visible.map { state ->
            val box = state.boxes.last()
            Track(
                id = state.id,
                label = state.label,
                lastBox = box,
                age = state.age,
                lostFrames = 0,
                history = state.boxes,
                approaching = approachDetector.isApproaching(state.boxes, isUserMoving())
            )
        }
    }

    private fun associate(
        detections: List<Detection>,
        timestampMs: Long,
        unmatchedTrackIds: MutableSet<Int>,
        visible: MutableList<State>,
        usedDetections: MutableSet<Int>
    ) {
        data class Candidate(val trackId: Int, val detectionIndex: Int, val iou: Float)

        val candidates = tracks.values.asSequence()
            .filter { it.id in unmatchedTrackIds }
            .flatMap { state ->
                detections.indices.asSequence().map { index ->
                    Candidate(state.id, index, intersectionOverUnion(predictedBox(state), detections[index]))
                }
            }
            .filter { it.iou >= config.matchIou }
            .sortedByDescending { it.iou }
            .toList()
        for (candidate in candidates) {
            if (candidate.trackId !in unmatchedTrackIds || candidate.detectionIndex in usedDetections) continue
            val state = tracks.getValue(candidate.trackId)
            val detection = detections[candidate.detectionIndex].copy(timestampMs = timestampMs)
            state.boxes = (state.boxes + detection).takeLast(config.historySize)
            state.age++
            state.lostFrames = 0
            unmatchedTrackIds.remove(state.id)
            usedDetections += candidate.detectionIndex
            visible += state
        }
    }

    private fun intersectionOverUnion(a: Detection, b: Detection): Float {
        if (a.label != b.label) return 0f
        val left = maxOf(a.x, b.x)
        val top = maxOf(a.y, b.y)
        val right = minOf(a.x + a.w, b.x + b.w)
        val bottom = minOf(a.y + a.h, b.y + b.h)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.w * a.h + b.w * b.h - intersection
        return if (union > 0f) intersection / union else 0f
    }

    /** Extrapolate box edges by the last observed per-frame change across a short gap. */
    private fun predictedBox(state: State): Detection {
        if (state.boxes.size < 2 || state.lostFrames <= 0) return state.boxes.last()
        val previous = state.boxes[state.boxes.lastIndex - 1]
        val last = state.boxes.last()
        val steps = state.lostFrames.toFloat()
        return last.copy(
            x = (last.x + (last.x - previous.x) * steps).coerceAtLeast(0f),
            y = (last.y + (last.y - previous.y) * steps).coerceAtLeast(0f),
            w = (last.w + (last.w - previous.w) * steps).coerceAtLeast(1f),
            h = (last.h + (last.h - previous.h) * steps).coerceAtLeast(1f)
        )
    }
}

/** Requires sustained area growth across multiple observations; walking raises the threshold to
 * reduce false approach alerts from ego-motion. This intentionally reports a qualitative event,
 * never speed or time-to-collision.
 */
class ApproachDetector(private val config: TrackerConfig = TrackerConfig.load()) {
    fun isApproaching(history: List<Detection>, userMoving: Boolean): Boolean {
        if (history.size < config.approachMinObservations || !history.last().label.isVehicle()) return false
        val first = history.first()
        val last = history.last()
        if (last.timestampMs - first.timestampMs < config.approachMinSpanMs) return false

        val half = history.size / 2
        val earlyArea = history.take(half).map(::area).average()
        val lateArea = history.takeLast(half).map(::area).average()
        if (earlyArea <= 0.0 || lateArea <= earlyArea) return false

        val positiveSteps = history.zipWithNext().count { (a, b) -> area(b) > area(a) }
        val requiredPositiveSteps = (history.size - 1) * 2 / 3
        val growthThreshold = if (userMoving) config.approachGrowthRatioWhileMoving else config.approachGrowthRatio
        return positiveSteps >= requiredPositiveSteps && lateArea / earlyArea >= growthThreshold
    }

    private fun area(box: Detection): Double = box.w.toDouble() * box.h.toDouble()
}

private fun String.isVehicle(): Boolean = lowercase() in setOf(
    "vehicle",
    "car",
    "truck",
    "bus",
    "motorcycle",
    "motorbike",
    "bicycle"
)
