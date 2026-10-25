package avn.app.vision

import avn.contracts.DistanceBand

/**
 * architecture.md WS-06 / AVN-43: "Distance band: box height or area relative to frame height,
 * per class, thresholds calibrated for the fixed chest-mount position: near | medium | far, never
 * meters." Boxes only, no images.
 *
 * Two independent signals are checked against [thresholds] -- height ratio (box height / frame
 * height) and bottom-edge ratio ((box y + box height) / frame height) -- because either alone can
 * mislead on this chest-mount rig (a tall but distant object low in the frame due to camera tilt;
 * a box clipped at the top of the frame that reads shorter than the real object is). Whichever
 * signal would classify the box as *nearer* wins the band. On most real boxes both signals agree
 * (something closer is both taller and lower in frame), but when they disagree this is a
 * deliberate bias toward the nearer band: under-warning about a close hazard is worse than
 * over-warning about a far one -- the same bias WS-14 applies to face recognition ("unknown" over
 * a wrong name) and WS-09 applies to currency ("unsure" over a wrong denomination).
 */
class DistanceBander(private val thresholds: BandThresholds = BandThresholds.load()) {
    fun estimate(box: Detection, frameHeightPx: Float): DistanceBand {
        val tier = thresholds.forClass(box.label)
        val heightRatio = box.h / frameHeightPx
        val bottomRatio = (box.y + box.h) / frameHeightPx
        val byHeight = bandFor(heightRatio, tier.near.heightRatio, tier.medium.heightRatio)
        val byBottom = bandFor(bottomRatio, tier.near.bottomRatio, tier.medium.bottomRatio)
        return nearerOf(byHeight, byBottom)
    }

    private fun bandFor(ratio: Float, nearThreshold: Float, mediumThreshold: Float): DistanceBand = when {
        ratio >= nearThreshold -> DistanceBand.NEAR
        ratio >= mediumThreshold -> DistanceBand.MEDIUM
        else -> DistanceBand.FAR
    }

    /** [DistanceBand]'s declared order is already nearest-first (NEAR, MEDIUM, FAR), so the
     * nearer of two bands is simply the smaller ordinal. */
    private fun nearerOf(a: DistanceBand, b: DistanceBand): DistanceBand = if (a.ordinal <= b.ordinal) a else b
}
