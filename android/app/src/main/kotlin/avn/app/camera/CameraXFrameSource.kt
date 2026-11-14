package avn.app.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import avn.app.core.logging.JsonLinesLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** On-demand, in-memory CameraX capture used by OCR and object detection. */
@Singleton
class CameraXFrameSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: JsonLinesLogger
) : FrameSource {
    @Volatile private var imageCapture: ImageCapture? = null
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(context)

    override fun bind(lifecycleOwner: LifecycleOwner) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val cameraProvider = providerFuture.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation((lifecycleOwner as? Activity)?.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    capture
                )
                imageCapture = capture
                log.log("camera", "camerax_bound")
            }.onFailure { error ->
                imageCapture = null
                log.log("camera", "camerax_bind_failed", fields = mapOf("error" to error.javaClass.simpleName))
            }
        }, callbackExecutor)
    }

    override fun frames(): Flow<Frame> = emptyFlow()

    override suspend fun captureStill(): Frame? {
        val capture = imageCapture ?: return null
        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(callbackExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame = try {
                        Frame(
                            bitmap = image.toBitmap(),
                            rotationDegrees = image.imageInfo.rotationDegrees,
                            timestampMs = System.currentTimeMillis()
                        )
                    } catch (error: Exception) {
                        log.log("camera", "frame_conversion_failed", fields = mapOf("error" to error.javaClass.simpleName))
                        null
                    } finally {
                        image.close()
                    }
                    if (continuation.isActive) continuation.resume(frame)
                }

                override fun onError(exception: ImageCaptureException) {
                    log.log("camera", "capture_failed", fields = mapOf("error" to exception.imageCaptureError))
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }
}
