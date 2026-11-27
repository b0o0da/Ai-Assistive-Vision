package avn.app.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import avn.app.core.logging.JsonLinesLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Live CameraX capture (WS-04/WS05-09). [frames] is the continuous analyzer stream the hazard
 * pipeline consumes; [captureStill] is the separate, on-demand single shot the OCR and the debug
 * "detect objects" screen use. CameraX replaces the *whole* bound use-case set on every
 * [ProcessCameraProvider.bindToLifecycle] call, so both use cases are tracked here and [rebind]
 * re-binds whichever of them currently exist, in whatever order [bind] and [frames] happen to be
 * called or torn down.
 */
@Singleton
class CameraXFrameSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: JsonLinesLogger
) : FrameSource {
    @Volatile private var imageCapture: ImageCapture? = null

    @Volatile private var imageAnalysis: ImageAnalysis? = null

    @Volatile private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var boundLifecycleOwner: LifecycleOwner? = null
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(context)

    override fun bind(lifecycleOwner: LifecycleOwner) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        boundLifecycleOwner = lifecycleOwner

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                cameraProvider = providerFuture.get()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation((lifecycleOwner as? Activity)?.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                rebind()
                log.log("camera", "camerax_bound")
            }.onFailure { error ->
                imageCapture = null
                log.log("camera", "camerax_bind_failed", fields = mapOf("error" to error.javaClass.simpleName))
            }
        }, callbackExecutor)
    }

    /**
     * Drop-oldest (android/CLAUDE.md architecture rule 3): [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST]
     * means a frame CameraX cannot hand to a still-busy analyzer is discarded, never queued -- a
     * slow consumer (detector inference) never back-pressures the camera.
     */
    override fun frames(): Flow<Frame> = callbackFlow {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(callbackExecutor) { image ->
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
            if (frame != null) trySend(frame)
        }
        imageAnalysis = analysis
        callbackExecutor.execute { rebind() }
        awaitClose {
            analysis.clearAnalyzer()
            imageAnalysis = null
            callbackExecutor.execute { rebind() }
        }
    }.conflate()

    /** Must only run on [callbackExecutor] (the main thread) -- CameraX binding is not thread-safe. */
    private fun rebind() {
        val provider = cameraProvider ?: return
        val owner = boundLifecycleOwner ?: return
        val useCases: Array<UseCase> = listOfNotNull(imageCapture, imageAnalysis).toTypedArray()
        provider.unbindAll()
        if (useCases.isEmpty()) return
        runCatching {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
        }.onFailure { error ->
            log.log("camera", "camerax_rebind_failed", fields = mapOf("error" to error.javaClass.simpleName))
        }
    }

    override suspend fun captureStill(): Frame? {
        val capture = imageCapture ?: return null
        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                callbackExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val frame = try {
                            Frame(
                                bitmap = image.toBitmap(),
                                rotationDegrees = image.imageInfo.rotationDegrees,
                                timestampMs = System.currentTimeMillis()
                            )
                        } catch (error: Exception) {
                            log.log(
                                "camera",
                                "frame_conversion_failed",
                                fields = mapOf(
                                    "error" to error.javaClass.simpleName
                                )
                            )
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
                }
            )
        }
    }
}
