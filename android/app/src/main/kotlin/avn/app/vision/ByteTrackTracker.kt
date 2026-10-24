package avn.app.vision

/**
 * ByteTrack-style tracker (architecture.md WS-06, AVN-40): turns per-frame [Detection] boxes into
 * stable [Track] identities. Boxes only, no images (WS-06's "Watch out").
 *
 * Association is greedy IoU matching, same label only, highest IoU first -- but matching is done
 * against a *predicted* box, not the track's raw last box. Each track with at least two history
 * entries extrapolates its last known velocity (dx/dt, dy/dt, dw/dt, dh/dt) forward to the new
 * frame's timestamp before computing IoU. Without this, two objects on a collision course (the
 * "crossing" scenario the ticket asks to test) can end up closer to *each other's* last position
 * than to their own by the time their paths cross, which swaps their IDs; a constant-velocity
 * prediction (the cheap stand-in for ByteTrack's Kalman filter) keeps each track closest to where
 * it is actually going. The same extrapolation is what lets a track survive a short occlusion
 * (missed detections for a few frames) and still re-associate correctly once the object
 * reappears, because the predicted box is carried forward by elapsed time, not frame count.
 *
 * A track that goes unmatched for more than [maxLostFrames] consecutive frames is dropped for
 * good (no re-queue -- a dropped id never comes back; a reappearing object becomes a new track).
 * [history] is capped at [maxHistory] entries so long-lived tracks do not grow unbounded.
 *
 * Stateful and not thread-safe, like the engine's other per-session components -- callers own one
 * instance per camera session.
 */
class ByteTrackTracker(
    private val iouThreshold: Double = 0.3,
    private val maxLostFrames: Int = 5,
    private val maxHistory: Int = 30
) : Tracker {

    private class LiveTrack(
        val id: Int,
        val label: String,
        val history: MutableList<Detection>,
        var age: Int,
        var lostFrames: Int
    ) {
        val lastBox: Detection get() = history.last()

        fun toTrack() = Track(
            id = id,
            label = label,
            history = history.toList(),
            lastBox = lastBox,
            age = age,
            lostFrames = lostFrames
        )
    }

    private val liveTracks = mutableListOf<LiveTrack>()
    private var nextId = 1

    override fun update(detections: List<Detection>, timestampMs: Long): List<Track> {
        val predicted = liveTracks.map { predict(it, timestampMs) }

        data class Candidate(val trackIndex: Int, val detectionIndex: Int, val iou: Double)
        val candidates = mutableListOf<Candidate>()
        for (trackIndex in liveTracks.indices) {
            val track = liveTracks[trackIndex]
            val predictedBox = predicted[trackIndex]
            for (detectionIndex in detections.indices) {
                val detection = detections[detectionIndex]
                if (detection.label != track.label) continue
                val overlap = iou(predictedBox, detection)
                if (overlap >= iouThreshold) {
                    candidates += Candidate(trackIndex, detectionIndex, overlap)
                }
            }
        }
        candidates.sortByDescending { it.iou }

        val unmatchedTracks = liveTracks.indices.toMutableSet()
        val unmatchedDetections = detections.indices.toMutableSet()
        val trackForDetection = HashMap<Int, Int>()
        for (candidate in candidates) {
            if (candidate.trackIndex !in unmatchedTracks || candidate.detectionIndex !in unmatchedDetections) {
                continue
            }
            unmatchedTracks -= candidate.trackIndex
            unmatchedDetections -= candidate.detectionIndex
            trackForDetection[candidate.detectionIndex] = candidate.trackIndex
        }

        val result = mutableListOf<Track>()

        for ((detectionIndex, trackIndex) in trackForDetection) {
            val track = liveTracks[trackIndex]
            track.history += detections[detectionIndex]
            if (track.history.size > maxHistory) track.history.removeAt(0)
            track.age += 1
            track.lostFrames = 0
            result += track.toTrack()
        }

        for (trackIndex in unmatchedTracks) {
            liveTracks[trackIndex].lostFrames += 1
        }
        liveTracks.removeAll { it.lostFrames > maxLostFrames }

        for (detectionIndex in unmatchedDetections) {
            val detection = detections[detectionIndex]
            val track = LiveTrack(
                id = nextId++,
                label = detection.label,
                history = mutableListOf(detection),
                age = 1,
                lostFrames = 0
            )
            liveTracks += track
            result += track.toTrack()
        }

        return result
    }

    /** Constant-velocity extrapolation of a track's box to [timestampMs]; see the class doc. */
    private fun predict(track: LiveTrack, timestampMs: Long): Detection {
        if (track.history.size < 2) return track.lastBox
        val previous = track.history[track.history.size - 2]
        val last = track.history[track.history.size - 1]
        val historyDeltaMs = last.timestampMs - previous.timestampMs
        if (historyDeltaMs <= 0) return last
        val forwardMs = timestampMs - last.timestampMs
        val scale = forwardMs.toFloat() / historyDeltaMs.toFloat()
        return last.copy(
            x = last.x + (last.x - previous.x) * scale,
            y = last.y + (last.y - previous.y) * scale,
            w = (last.w + (last.w - previous.w) * scale).coerceAtLeast(1f),
            h = (last.h + (last.h - previous.h) * scale).coerceAtLeast(1f)
        )
    }

    private fun iou(a: Detection, b: Detection): Double {
        val intersectLeft = maxOf(a.x, b.x)
        val intersectTop = maxOf(a.y, b.y)
        val intersectRight = minOf(a.x + a.w, b.x + b.w)
        val intersectBottom = minOf(a.y + a.h, b.y + b.h)
        val intersectWidth = (intersectRight - intersectLeft).coerceAtLeast(0f)
        val intersectHeight = (intersectBottom - intersectTop).coerceAtLeast(0f)
        val intersectArea = (intersectWidth * intersectHeight).toDouble()
        val unionArea = (a.w * a.h).toDouble() + (b.w * b.h).toDouble() - intersectArea
        return if (unionArea <= 0.0) 0.0 else intersectArea / unionArea
    }
}
