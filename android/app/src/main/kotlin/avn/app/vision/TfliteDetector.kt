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
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate

/** Which TFLite backend actually ran the model (WS05-04: "measure ms/frame per delegate"). */
enum class TfliteAccelerator { GPU, NNAPI, CPU_XNNPACK }

/**
 * WS05-04 spike: the same [Detector] contract as [YoloV8OnnxDetector], backed by LiteRT
 * (`org.tensorflow:tensorflow-lite`) instead of ONNX Runtime, to measure whether a GPU or NNAPI
 * delegate beats the already-shipped ONNX/XNNPACK CPU path (`models/manifest.json`'s "real
 * NPU/NNAPI delegate acceleration ... still unverified" note). Not wired into
 * [avn.app.core.di.ModulesModule] -- this is a latency spike, not a swap of the production
 * detector; see `docs/perf/detector-latency.md` for the numbers and the conclusion.
 *
 * [accelerator] is the backend to try first; [createInterpreter] falls back GPU -> NNAPI -> CPU
 * (XNNPACK, TFLite's default CPU delegate) exactly like [YoloV8OnnxDetector] falls back NNAPI ->
 * CPU, so a delegate unsupported on a given device degrades instead of crashing. [activeAccelerator]
 * reports which backend actually ended up running, for the latency report to label correctly.
 */
class TfliteDetector(
    private val context: Context,
    private val log: JsonLinesLogger,
    private val config: DetectorConfig,
    private val perf: PerfLogger,
    private val accelerator: TfliteAccelerator = TfliteAccelerator.CPU_XNNPACK,
    private val numThreads: Int = 4
) : Detector {
    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var gpuDelegate: GpuDelegate? = null

    @Volatile
    private var nnApiDelegate: NnApiDelegate? = null

    @Volatile
    var activeAccelerator: TfliteAccelerator = accelerator
        private set

    /** Frames that arrive while an inference is already running are dropped, not queued (WS05-04). */
    @Volatile
    private var busy = false

    override suspend fun detect(frame: Frame): List<Detection> = withContext(Dispatchers.Default) {
        val source = frame.bitmap ?: return@withContext emptyList()
        if (busy) return@withContext emptyList()
        busy = true
        try {
            val activeInterpreter = getOrCreateInterpreter()
            perf.measure("detector", "inference_ms") {
                val upright = rotateIfNeeded(source, frame.rotationDegrees)
                val prepared = letterbox(upright, config.inputSize)
                try {
                    val input = bitmapToNhwc(prepared.bitmap, config.inputSize)
                    val outputShape = activeInterpreter.getOutputTensor(0).shape()
                    val output = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                    activeInterpreter.run(input, output)
                    YoloDecoder.decode(
                        output[0],
                        upright.width,
                        upright.height,
                        frame.timestampMs,
                        prepared.scale,
                        prepared.padX,
                        prepared.padY,
                        config
                    )
                } finally {
                    prepared.bitmap.recycle()
                    if (upright !== source) upright.recycle()
                }
            }
        } catch (error: Exception) {
            log.log("detector", "inference_failed", fields = mapOf("error" to error.javaClass.simpleName))
            emptyList()
        } finally {
            busy = false
        }
    }

    private fun getOrCreateInterpreter(): Interpreter {
        interpreter?.let { return it }
        return synchronized(this) {
            interpreter?.let { return@synchronized it }
            val modelBuffer = loadModelFile(config.modelAsset)
            val created = createInterpreter(modelBuffer, accelerator)
            warmUp(created)
            interpreter = created
            log.log(
                "detector",
                "model_loaded",
                fields = mapOf("input" to config.inputSize, "accelerator" to activeAccelerator.name)
            )
            created
        }
    }

    /** Runs one dummy inference at service start so the first real frame is not the slow one. */
    private fun warmUp(created: Interpreter) {
        val dummyInput = Array(1) { Array(config.inputSize) { Array(config.inputSize) { FloatArray(3) } } }
        val outputShape = created.getOutputTensor(0).shape()
        val dummyOutput = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        created.run(dummyInput, dummyOutput)
    }

    private fun createInterpreter(modelBuffer: MappedByteBuffer, requested: TfliteAccelerator): Interpreter {
        return when (requested) {
            TfliteAccelerator.GPU -> {
                val compatList = CompatibilityList()
                if (!compatList.isDelegateSupportedOnThisDevice) {
                    log.log("detector", "gpu_delegate_unsupported", fields = emptyMap())
                    return createInterpreter(modelBuffer, TfliteAccelerator.NNAPI)
                }
                try {
                    val delegate = GpuDelegate()
                    gpuDelegate = delegate
                    val options = Interpreter.Options().setNumThreads(numThreads).addDelegate(delegate)
                    val created = Interpreter(modelBuffer, options)
                    activeAccelerator = TfliteAccelerator.GPU
                    created
                } catch (error: Exception) {
                    log.log("detector", "gpu_delegate_failed", fields = mapOf("error" to error.javaClass.simpleName))
                    createInterpreter(modelBuffer, TfliteAccelerator.NNAPI)
                }
            }
            TfliteAccelerator.NNAPI -> try {
                val delegate = NnApiDelegate()
                nnApiDelegate = delegate
                val options = Interpreter.Options().setNumThreads(numThreads).addDelegate(delegate)
                val created = Interpreter(modelBuffer, options)
                activeAccelerator = TfliteAccelerator.NNAPI
                created
            } catch (error: Exception) {
                log.log("detector", "nnapi_delegate_failed", fields = mapOf("error" to error.javaClass.simpleName))
                createInterpreter(modelBuffer, TfliteAccelerator.CPU_XNNPACK)
            }
            TfliteAccelerator.CPU_XNNPACK -> {
                val options = Interpreter.Options().setNumThreads(numThreads)
                activeAccelerator = TfliteAccelerator.CPU_XNNPACK
                Interpreter(modelBuffer, options)
            }
        }
    }

    private fun loadModelFile(assetName: String): MappedByteBuffer {
        val fd = context.assets.openFd(assetName)
        return FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private data class Letterboxed(val bitmap: Bitmap, val scale: Float, val padX: Float, val padY: Float)

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

    /** NHWC, [0,1]-normalized RGB -- LiteRT's channels-last layout, unlike ONNX Runtime's NCHW
     * (see [YoloV8OnnxDetector.bitmapToChw]); `onnx2tf` transposes the model, not the pixels. */
    private fun bitmapToNhwc(bitmap: Bitmap, size: Int): Array<Array<Array<FloatArray>>> {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        var i = 0
        return Array(1) {
            Array(size) {
                Array(size) {
                    val pixel = pixels[i++]
                    floatArrayOf(
                        (pixel shr 16 and 0xff) / 255f,
                        (pixel shr 8 and 0xff) / 255f,
                        (pixel and 0xff) / 255f
                    )
                }
            }
        }
    }
}
