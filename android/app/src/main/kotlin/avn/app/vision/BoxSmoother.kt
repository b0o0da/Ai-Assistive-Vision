package avn.app.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Numbers for [BoxSmoother], from `config/smoothing.json` (PROPOSED). */
data class SmoothingConfig(val alpha: Float, val resetIou: Float, val forgetAfterCalls: Int) {
    /** Steady-state lag of the smoother on constant motion, in frames: (1 - alpha) / alpha. */
    val lagFrames: Float get() = (1f - alpha) / alpha

    companion object {
        val DEFAULT = SmoothingConfig(alpha = 0.5f, resetIou = 0.3f, forgetAfterCalls = 10)

        fun load(resourcePath: String = "/config/smoothing.json"): SmoothingConfig {
            val text = SmoothingConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return DEFAULT
            return runCatching {
                val root = Json.parseToJsonElement(text).jsonObject
                SmoothingConfig(
                    alpha = root.getValue("alpha").jsonPrimitive.float,
                    resetIou = root.getValue("resetIou").jsonPrimitive.float,
                    forgetAfterCalls = root.getValue("forgetAfterCalls").jsonPrimitive.int
                ).also { require(it.alpha in 0.05f..1f && it.resetIou in 0f..1f && it.forgetAfterCalls > 0) }
            }.getOrDefault(DEFAULT)
        }
    }
}

/**
 * Per-track exponential smoothing of box edges (WS06-03), so detector jitter does not make the
 * distance band flip between near and medium from one frame to the next.
 *
 * - Each track ID keeps its own smoothed box: `s = alpha * raw + (1 - alpha) * s`.
 * - With alpha 0.5 the smoother lags constant motion by 1 frame (< 2, the acceptance limit) and
 *   cuts white-noise variance to alpha / (2 - alpha) = 1/3 of the raw value.
 * - A big jump (IoU with the smoothed box below `resetIou`) snaps straight to the new box: a real,
 *   sudden change is never averaged away.
 * - Tracks not seen for `forgetAfterCalls` calls are forgotten.
 * Only box geometry is kept; label, confidence and timestamp come from the latest raw box.
 */
class BoxSmoother(private val config: SmoothingConfig = SmoothingConfig.load()) {
    private data class Entry(var box: Detection, var lastCall: Long)

    private val state = HashMap<Int, Entry>()
    private var call = 0L

    fun smooth(trackId: Int, raw: Detection): Detection {
        val prev = state[trackId]
        val next = if (prev == null || YoloDecoder.intersectionOverUnion(prev.box, raw) < config.resetIou) {
            raw
        } else {
            val a = config.alpha
            val p = prev.box
            raw.copy(
                x = a * raw.x + (1 - a) * p.x,
                y = a * raw.y + (1 - a) * p.y,
                w = a * raw.w + (1 - a) * p.w,
                h = a * raw.h + (1 - a) * p.h
            )
        }
        state[trackId] = Entry(next, call)
        return next
    }

    /** Smooths every track's [Track.lastBox] for this frame and forgets tracks gone too long. */
    fun smoothTracks(tracks: List<Track>): List<Track> {
        call++
        val out = tracks.map { t -> t.copy(lastBox = smooth(t.id, t.lastBox)) }
        state.entries.removeIf { call - it.value.lastCall >= config.forgetAfterCalls }
        return out
    }

    /** Number of tracks currently remembered; for tests. */
    val size: Int get() = state.size
}
