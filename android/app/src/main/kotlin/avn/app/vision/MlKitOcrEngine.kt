package avn.app.vision

import android.graphics.Bitmap
import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import avn.app.perf.PerfLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// WS07-03: ML Kit on-device text recognition behind OcrEngine (WS07-01).

/** Numbers for [MlKitOcrEngine], from `config/ocr.json` (PROPOSED). */
data class OcrConfig(val minLineConfidence: Double, val blurSampleSize: Int, val minLaplacianVariance: Double) {
    companion object {
        val DEFAULT = OcrConfig(minLineConfidence = 0.5, blurSampleSize = 256, minLaplacianVariance = 40.0)

        fun load(resourcePath: String = "/config/ocr.json"): OcrConfig {
            val text = OcrConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return DEFAULT
            return runCatching {
                val root = Json.parseToJsonElement(text).jsonObject
                val blur = root.getValue("blur").jsonObject
                OcrConfig(
                    minLineConfidence = root.getValue("minLineConfidence").jsonPrimitive.double,
                    blurSampleSize = blur.getValue("sampleSize").jsonPrimitive.int,
                    minLaplacianVariance = blur.getValue("minLaplacianVariance").jsonPrimitive.double
                )
            }.getOrDefault(DEFAULT)
        }
    }
}

/** One line as the recognizer returns it, before mapping. Box in frame pixels; confidence may be missing. */
data class RecognizedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float?
)

/** The ML Kit call, behind a seam so the mapping and the no-text paths are tested on a laptop. */
fun interface TextRecognizerPort {
    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<RecognizedLine>
}

/**
 * Real recognizer: ML Kit text recognition v2 with the **bundled** Latin model
 * (`com.google.mlkit:text-recognition`), so it runs fully on the phone and works offline.
 * The client is created lazily, off the main thread, on first use.
 */
class MlKitTextRecognizer : TextRecognizerPort {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<RecognizedLine> =
        suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, rotationDegrees))
                .addOnSuccessListener { text ->
                    val lines = text.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            RecognizedLine(line.text, box.left, box.top, box.right, box.bottom, line.confidence)
                        }
                    }
                    cont.resume(lines)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}

/** Pure mapping from recognizer lines to an [OcrReading]; no Android types. */
object OcrLineMapper {
    /** Same-row tolerance when ordering lines: a fraction of the median line height. */
    private const val ROW_TOLERANCE = 0.5

    fun toReading(lines: List<RecognizedLine>, minConfidence: Double, source: String): OcrReading {
        val usable = lines.mapNotNull { line ->
            val text = line.text.replace(Regex("\\s+"), " ").trim()
            val confidence = line.confidence?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0
            val w = (line.right - line.left).toFloat()
            val h = (line.bottom - line.top).toFloat()
            if (text.isEmpty() || confidence < minConfidence || w <= 0f || h <= 0f) {
                null
            } else {
                OcrBlock(text, TextBox(line.left.toFloat(), line.top.toFloat(), w, h), confidence)
            }
        }
        if (usable.isEmpty()) return OcrReading.empty(source).copy(noText = NoTextReason.NO_TEXT)
        return OcrReading(readingOrder(usable), language = "en", source = source)
    }

    /** Top to bottom; lines on the same row (centres within half a line height) left to right. */
    fun readingOrder(blocks: List<OcrBlock>): List<OcrBlock> {
        val heights = blocks.map { it.box.h }.sorted()
        val tolerance = heights[heights.size / 2] * ROW_TOLERANCE
        val rows = mutableListOf<MutableList<OcrBlock>>()
        for (b in blocks.sortedBy { it.box.y + it.box.h / 2 }) {
            val centre = b.box.y + b.box.h / 2
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(centre - row.first().let { it.box.y + it.box.h / 2 }) <= tolerance) {
                row += b
            } else {
                rows += mutableListOf(b)
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.box.x } }
    }
}

/** Sharpness check: variance of the Laplacian on a small grayscale copy. Low variance = blurry or flat. */
object Sharpness {
    fun laplacianVariance(gray: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Grayscale (0..255) copy of [bitmap], downscaled so the longer side is at most [maxSide]. */
    fun grayOf(bitmap: Bitmap, maxSide: Int): Triple<IntArray, Int, Int> {
        val scale = min(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val w = maxOf(1, (bitmap.width * scale).toInt())
        val h = maxOf(1, (bitmap.height * scale).toInt())
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val gray = IntArray(px.size) { i ->
            val p = px[i]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        return Triple(gray, w, h)
    }
}

/**
 * The real [OcrEngine]. Order of checks, each ending in a clear no-text result instead of an error:
 * no frame -> [NoTextReason.EMPTY_FRAME]; too blurry or flat -> [NoTextReason.BLURRY] (ML Kit is not
 * called); recognizer failure -> [NoTextReason.ENGINE_ERROR]; nothing usable -> [NoTextReason.NO_TEXT].
 * Latency is written through [PerfLogger] (`ocr` / `perf_read_ms`). Only counts and timings are
 * logged, never the text.
 */
class MlKitOcrEngine(
    private val recognizer: TextRecognizerPort,
    private val log: JsonLinesLogger,
    private val perf: PerfLogger? = null,
    private val config: OcrConfig = OcrConfig.load(),
    private val gray: (Bitmap, Int) -> Triple<IntArray, Int, Int> = Sharpness::grayOf
) : OcrEngine {
    override suspend fun read(frame: Frame): OcrReading = withContext(Dispatchers.Default) {
        val bitmap = frame.bitmap
            ?: return@withContext OcrReading.empty(SOURCE).copy(noText = NoTextReason.EMPTY_FRAME)
        val (g, w, h) = gray(bitmap, config.blurSampleSize)
        val variance = Sharpness.laplacianVariance(g, w, h)
        if (variance < config.minLaplacianVariance) {
            log.log("ocr", "no_text", fields = mapOf("reason" to "blurry"))
            return@withContext OcrReading.empty(SOURCE).copy(noText = NoTextReason.BLURRY)
        }
        val start = System.nanoTime()
        val lines = try {
            recognizer.recognize(bitmap, frame.rotationDegrees)
        } catch (e: Exception) {
            log.log("ocr", "read_failed", fields = mapOf("error" to e.javaClass.simpleName))
            return@withContext OcrReading.empty(SOURCE).copy(noText = NoTextReason.ENGINE_ERROR)
        }
        perf?.record("ocr", "read_ms", (System.nanoTime() - start) / 1_000_000)
        val reading = OcrLineMapper.toReading(lines, config.minLineConfidence, SOURCE)
        log.log("ocr", "read", fields = mapOf("blocks" to reading.blocks.size))
        reading
    }

    companion object {
        const val SOURCE = "mlkit"
    }
}
