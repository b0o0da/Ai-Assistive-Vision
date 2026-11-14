package avn.app.camera

import android.graphics.Bitmap
import androidx.lifecycle.LifecycleOwner
import avn.app.core.logging.JsonLinesLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** In memory only. Frames are never written to disk. */
class Frame(val bitmap: Bitmap?, val rotationDegrees: Int, val timestampMs: Long)

interface FrameSource {
    /** Attach the camera to the foreground activity. Fakes intentionally do nothing. */
    fun bind(lifecycleOwner: LifecycleOwner)

    /** Drop-oldest: a slow consumer never back-pressures the camera. */
    fun frames(): Flow<Frame>

    suspend fun captureStill(): Frame?
}

/** Fake in-memory source for tests. The normal debug app binds [CameraXFrameSource]. */
@Singleton
class FakeFrameSource @Inject constructor(private val log: JsonLinesLogger) : FrameSource {
    override fun bind(lifecycleOwner: LifecycleOwner) = Unit

    override fun frames(): Flow<Frame> = emptyFlow()

    override suspend fun captureStill(): Frame? {
        log.log("camera", "fake_capture_still")
        return null
    }
}
