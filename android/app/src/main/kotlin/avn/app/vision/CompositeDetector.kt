package avn.app.vision

import avn.app.camera.Frame
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs several [Detector]s against the same [Frame] and concatenates their detections. Used to
 * combine the general-object model and the dedicated pothole model (two different checkpoints,
 * two disjoint class sets) behind the single [Detector] seam the rest of the app depends on --
 * see [avn.app.core.di.ModulesModule]'s `detector()` provider.
 */
class CompositeDetector(private val detectors: List<Detector>) : Detector {
    override suspend fun detect(frame: Frame): List<Detection> = coroutineScope {
        detectors.map { detector -> async { detector.detect(frame) } }
            .map { it.await() }
            .flatten()
    }
}
