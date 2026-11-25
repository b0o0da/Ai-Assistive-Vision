package avn.app.vision

import avn.contracts.Side

/**
 * architecture.md WS-06 / AVN-43: "Side: box centre x in thirds of the frame (left/center/right)."
 * No calibration needed -- thirds are purely frame-width-relative, not device- or class-specific,
 * unlike [DistanceBander]. Boxes only, no images.
 */
class SideEstimator {
    fun estimate(box: Detection, frameWidthPx: Float): Side {
        val centerX = box.x + box.w / 2f
        val third = frameWidthPx / 3f
        return when {
            centerX < third -> Side.LEFT
            centerX < third * 2f -> Side.CENTER
            else -> Side.RIGHT
        }
    }
}
