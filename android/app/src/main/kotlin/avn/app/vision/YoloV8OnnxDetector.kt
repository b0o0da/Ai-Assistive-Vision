package avn.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import avn.app.perf.PerfLogger
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device ONNX inference for one [DetectorConfig]/model pair. Not Hilt-constructor-injected:
 * the app runs more than one of these at once (the general-object model and the dedicated
 * pothole model are two separate checkpoints with two separate class sets -- see
 * [ModulesModule][avn.app.core.di.ModulesModule]'s `detector()` provider, which constructs both
 * directly and combines them with [CompositeDetector]), so the constructor takes its [config]
 * explicitly instead of always loading the same resource path.
 */
class YoloV8OnnxDetector(
    private val context: Context,
    private val log: JsonLinesLogger,
    private val config: DetectorConfig,
    private val perf: PerfLogger
) : Detector {
    private val env: OrtEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OrtEnvironment.getEnvironment()
    }

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var inputName: String? = null

    @Volatile
    private var sessionOptions: OrtSession.SessionOptions? = null

    override suspend fun detect(frame: Frame): List<Detection> = withContext(Dispatchers.Default) {
        val source = frame.bitmap ?: return@withContext emptyList()
        try {
            val activeSession = getOrCreateSession()
            perf.measure("detector", "inference_ms") {
                val upright = rotateIfNeeded(source, frame.rotationDegrees)
                val prepared = letterbox(upright, config.inputSize)
                try {
                    val input = bitmapToChw(prepared.bitmap, config.inputSize)
                    OnnxTensor.createTensor(
                        env,
                        input,
                        longArrayOf(1, 3, config.inputSize.toLong(), config.inputSize.toLong())
                    ).use { tensor ->
                        activeSession.run(mapOf(inputName!! to tensor)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val output = result[0].value as Array<Array<FloatArray>>
                            YoloDecoder.decode(
                                output[0], upright.width, upright.height, frame.timestampMs,
                                prepared.scale, prepared.padX, prepared.padY, config
                            )
                        }
                    }
                } finally {
                    prepared.bitmap.recycle()
                    if (upright !== source) upright.recycle()
                }
            }
        } catch (error: Exception) {
            log.log("detector", "inference_failed", fields = mapOf("error" to error.javaClass.simpleName))
            emptyList()
        }
    }

    private fun getOrCreateSession(): OrtSession {
        session?.let { return it }
        return synchronized(this) {
            session?.let { return@synchronized it }
            val model = context.assets.open(config.modelAsset).use { it.readBytes() }
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(4)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            var optionsForSession = options
            val created = try {
                // NNAPI accelerates supported graph sections and ONNX Runtime falls back to CPU for
                // unsupported operators. If provider setup itself fails, create a CPU session.
                runCatching { options.addNnapi() }
                env.createSession(model, options)
            } catch (error: Exception) {
                log.log("detector", "nnapi_session_failed", fields = mapOf("error" to error.javaClass.simpleName))
                options.close()
                val cpuOptions = OrtSession.SessionOptions()
                cpuOptions.setIntraOpNumThreads(4)
                cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                optionsForSession = cpuOptions
                env.createSession(model, cpuOptions)
            }
            inputName = created.inputNames.firstOrNull()
                ?: error("YOLOv8n ONNX model has no input")
            // ONNX Runtime requires SessionOptions to outlive sessions created from them.
            sessionOptions = optionsForSession
            session = created
            log.log("detector", "model_loaded", fields = mapOf("input" to config.inputSize))
            created
        }
    }

    private data class Letterboxed(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private fun rotateIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun letterbox(source: Bitmap, size: Int): Letterboxed {
        val scale = min(size.toFloat() / source.width, size.toFloat() / source.height)
        val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val padX = (size - scaledWidth) / 2f
        val padY = (size - scaledHeight) / 2f
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            source,
            null,
            RectF(padX, padY, padX + scaledWidth, padY + scaledHeight),
            paint
        )
        return Letterboxed(output, scale, padX, padY)
    }

    private fun bitmapToChw(bitmap: Bitmap, size: Int): FloatBuffer {
        val count = size * size
        val buffer = ByteBuffer.allocateDirect(3 * count * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val pixels = IntArray(count)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (channel in 0..2) {
            for (pixel in pixels) {
                val value = when (channel) {
                    0 -> pixel shr 16 and 0xff
                    1 -> pixel shr 8 and 0xff
                    else -> pixel and 0xff
                }
                buffer.put(value / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }
}
