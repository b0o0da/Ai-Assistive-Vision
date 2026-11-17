package avn.app.vision

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One YUV_420_888 frame as CameraX's `ImageProxy` hands it over (three planes plus strides).
 * Plain arrays, so the preprocessing below runs and is tested on a laptop JVM with no phone.
 */
class YuvPlanes(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int = width,
    val uvRowStride: Int = (width + 1) / 2,
    val uvPixelStride: Int = 1
)

/**
 * Detector input: a `[1, 3, inputSize, inputSize]` float tensor in CHW order, values 0..1,
 * plus what [YoloDecoder] needs to map boxes back onto the upright frame.
 */
class Preprocessed(
    val tensor: FloatArray,
    val inputSize: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val uprightWidth: Int,
    val uprightHeight: Int
) {
    val shape: LongArray get() = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
}

/**
 * Frame preprocessing for the detector (WS05-06, architecture.md WS-05):
 * YUV_420_888 -> RGB (BT.601 full range, what CameraX delivers), rotate upright by the frame's
 * `rotationDegrees`, letterbox to a square of `inputSize` with grey 114 padding (the YOLO convention
 * [YoloV8OnnxDetector] already uses), bilinear resize with half-pixel centres, then normalize to 0..1 CHW.
 *
 * Rotation and resize are fused: each output pixel is sampled straight from the un-rotated source,
 * so no rotated copy of the frame is ever made. No Android imports; frames stay in memory only.
 */
object FramePreprocessor {
    const val PAD_VALUE = 114

    /** YUV_420_888 -> packed ARGB (0xFFRRGGBB), BT.601 full range, fixed-point. */
    fun yuvToArgb(p: YuvPlanes, out: IntArray = IntArray(p.width * p.height)): IntArray {
        require(out.size >= p.width * p.height) { "output too small" }
        var i = 0
        for (row in 0 until p.height) {
            val yRow = row * p.yRowStride
            val uvRow = (row shr 1) * p.uvRowStride
            for (col in 0 until p.width) {
                val yy = p.y[yRow + col].toInt() and 0xFF
                val uvIndex = uvRow + (col shr 1) * p.uvPixelStride
                val uu = (p.u[uvIndex].toInt() and 0xFF) - 128
                val vv = (p.v[uvIndex].toInt() and 0xFF) - 128
                // 1.402, 0.344136, 0.714136, 1.772 scaled by 2^16
                val r = yy + ((91881 * vv + 32768) shr 16)
                val g = yy - ((22554 * uu + 46802 * vv - 32768) shr 16)
                val b = yy + ((116130 * uu + 32768) shr 16)
                out[i++] = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
        return out
    }

    fun preprocess(frame: YuvPlanes, rotationDegrees: Int, inputSize: Int): Preprocessed =
        preprocess(yuvToArgb(frame), frame.width, frame.height, rotationDegrees, inputSize)

    /**
     * [argb] is the raw (un-rotated) frame, row-major, [width] x [height]. [rotationDegrees] is how far
     * the frame must turn clockwise to be upright (CameraX `ImageInfo.rotationDegrees`); any multiple of 90.
     */
    fun preprocess(
        argb: IntArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        inputSize: Int,
        out: FloatArray = FloatArray(3 * inputSize * inputSize)
    ): Preprocessed {
        require(width > 0 && height > 0 && argb.size >= width * height) { "empty or short frame" }
        require(out.size >= 3 * inputSize * inputSize) { "tensor too small" }
        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation % 90 == 0) { "rotation must be a multiple of 90, was $rotationDegrees" }
        val swap = rotation == 90 || rotation == 270
        val uw = if (swap) height else width
        val uh = if (swap) width else height

        val scale = min(inputSize.toFloat() / uw, inputSize.toFloat() / uh)
        val scaledW = (uw * scale).roundToInt().coerceIn(1, inputSize)
        val scaledH = (uh * scale).roundToInt().coerceIn(1, inputSize)
        val padX = (inputSize - scaledW) / 2
        val padY = (inputSize - scaledH) / 2

        // Per-column and per-row bilinear taps in upright coordinates, computed once.
        val x0 = IntArray(scaledW)
        val x1 = IntArray(scaledW)
        val fx = FloatArray(scaledW)
        fillTaps(scaledW, uw, x0, x1, fx)
        val y0 = IntArray(scaledH)
        val y1 = IntArray(scaledH)
        val fy = FloatArray(scaledH)
        fillTaps(scaledH, uh, y0, y1, fy)

        // Upright (ux, uy) -> raw index is linear: origin + ux * stepX + uy * stepY. Pre-multiply the taps
        // so the inner loop is plain array reads, with no rotation branch per pixel.
        val (origin, stepX, stepY) = when (rotation) {
            0 -> Triple(0, 1, width)
            90 -> Triple((height - 1) * width, -width, 1)
            180 -> Triple((height - 1) * width + width - 1, -1, -width)
            else -> Triple(width - 1, width, -1)
        }
        for (i in 0 until scaledW) {
            x0[i] *= stepX
            x1[i] *= stepX
        }
        for (i in 0 until scaledH) {
            y0[i] = origin + y0[i] * stepY
            y1[i] = origin + y1[i] * stepY
        }

        val plane = inputSize * inputSize
        val pad = PAD_VALUE / 255f
        out.fill(pad, 0, 3 * plane)
        val inv = 1f / 255f
        for (oy in 0 until scaledH) {
            val ya = y0[oy]
            val yb = y1[oy]
            val wy = fy[oy]
            val rowBase = (oy + padY) * inputSize + padX
            for (ox in 0 until scaledW) {
                val xa = x0[ox]
                val wx = fx[ox]
                val o = rowBase + ox
                if (wx == 0f && wy == 0f) {
                    // Sample lands exactly on a source pixel (always true at scale 1): one read.
                    val p = argb[ya + xa]
                    out[o] = inv * ch(p, 16)
                    out[plane + o] = inv * ch(p, 8)
                    out[2 * plane + o] = inv * ch(p, 0)
                    continue
                }
                val xb = x1[ox]
                val p00 = argb[ya + xa]
                val p10 = argb[ya + xb]
                val p01 = argb[yb + xa]
                val p11 = argb[yb + xb]
                val w00 = (1 - wx) * (1 - wy)
                val w10 = wx * (1 - wy)
                val w01 = (1 - wx) * wy
                val w11 = wx * wy
                out[o] = inv * (w00 * ch(p00, 16) + w10 * ch(p10, 16) + w01 * ch(p01, 16) + w11 * ch(p11, 16))
                out[plane + o] = inv * (w00 * ch(p00, 8) + w10 * ch(p10, 8) + w01 * ch(p01, 8) + w11 * ch(p11, 8))
                out[2 * plane + o] = inv * (w00 * ch(p00, 0) + w10 * ch(p10, 0) + w01 * ch(p01, 0) + w11 * ch(p11, 0))
            }
        }
        return Preprocessed(out, inputSize, scale, padX.toFloat(), padY.toFloat(), uw, uh)
    }

    /** Half-pixel-centre bilinear taps for resizing [src] samples to [dst]. */
    private fun fillTaps(dst: Int, src: Int, a: IntArray, b: IntArray, f: FloatArray) {
        val ratio = src.toFloat() / dst
        for (i in 0 until dst) {
            val s = ((i + 0.5f) * ratio - 0.5f).coerceIn(0f, (src - 1).toFloat())
            val lo = floor(s).toInt()
            a[i] = lo
            b[i] = min(lo + 1, src - 1)
            f[i] = s - lo
        }
    }

    private fun ch(p: Int, shift: Int): Float = ((p shr shift) and 0xFF).toFloat()

    private fun clamp(v: Int): Int = if (v < 0) {
        0
    } else if (v > 255) {
        255
    } else {
        v
    }
}
