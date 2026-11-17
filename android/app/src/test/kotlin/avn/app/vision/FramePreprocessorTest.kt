package avn.app.vision

import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notion test name: preprocess_reference_test (WS05-06). Pure JVM, no phone. */
class FramePreprocessorTest {
    private val size = 640

    // ---- synthetic YUV frames (seeded), the "5 frames" of the acceptance criteria ----

    private data class Spec(val name: String, val w: Int, val h: Int, val rotation: Int, val pattern: String)

    private val frames = listOf(
        Spec("vga_gradient_rot90", 640, 480, 90, "gradient"),
        Spec("hd_checker_rot0", 1280, 720, 0, "checker"),
        Spec("portrait_noise_rot270", 480, 640, 270, "noise"),
        Spec("fhd_bars_rot180", 1920, 1080, 180, "bars"),
        Spec("small_odd_noise_rot90", 321, 239, 90, "noise")
    )

    private fun rgbAt(spec: Spec, x: Int, y: Int, rnd: IntArray): Triple<Int, Int, Int> = when (spec.pattern) {
        "gradient" -> Triple(x * 255 / (spec.w - 1), y * 255 / (spec.h - 1), 128)
        "checker" -> if (((x / 40) + (y / 40)) % 2 == 0) Triple(230, 40, 40) else Triple(20, 200, 60)
        "bars" -> listOf(
            Triple(255, 255, 255),
            Triple(255, 255, 0),
            Triple(0, 255, 255),
            Triple(0, 255, 0),
            Triple(0, 0, 255)
        )[
            x *
                5 /
                spec.w
        ]
        else -> {
            val v = rnd[y * spec.w + x]
            Triple(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF)
        }
    }

    /** RGB -> YUV_420_888 (BT.601 full range), chroma averaged over each 2x2 block, odd sizes handled. */
    private fun makeYuv(spec: Spec): YuvPlanes {
        val rnd = Random(42 + spec.w).let { r -> IntArray(spec.w * spec.h) { r.nextInt() } }
        val cw = (spec.w + 1) / 2
        val chh = (spec.h + 1) / 2
        val y = ByteArray(spec.w * spec.h)
        val uSum = DoubleArray(cw * chh)
        val vSum = DoubleArray(cw * chh)
        val n = IntArray(cw * chh)
        for (row in 0 until spec.h) {
            for (col in 0 until spec.w) {
                val (r, g, b) = rgbAt(spec, col, row, rnd)
                y[row * spec.w + col] = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255).toByte()
                val c = (row / 2) * cw + col / 2
                uSum[c] += -0.168736 * r - 0.331264 * g + 0.5 * b + 128
                vSum[c] += 0.5 * r - 0.418688 * g - 0.081312 * b + 128
                n[c]++
            }
        }
        val u = ByteArray(cw * chh) { (uSum[it] / n[it]).roundToInt().coerceIn(0, 255).toByte() }
        val v = ByteArray(cw * chh) { (vSum[it] / n[it]).roundToInt().coerceIn(0, 255).toByte() }
        return YuvPlanes(spec.w, spec.h, y, u, v)
    }

    // ---- independent reference: doubles, explicit forward rotation, then letterbox ----

    private fun reference(p: YuvPlanes, rotation: Int, size: Int): DoubleArray {
        val w = p.width
        val h = p.height
        val rgb = Array(3) { DoubleArray(w * h) }
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yy = (p.y[row * w + col].toInt() and 0xFF).toDouble()
                val c = (row / 2) * ((w + 1) / 2) + col / 2
                val uu = (p.u[c].toInt() and 0xFF) - 128.0
                val vv = (p.v[c].toInt() and 0xFF) - 128.0
                rgb[0][row * w + col] = (yy + 1.402 * vv).coerceIn(0.0, 255.0)
                rgb[1][row * w + col] = (yy - 0.344136 * uu - 0.714136 * vv).coerceIn(0.0, 255.0)
                rgb[2][row * w + col] = (yy + 1.772 * uu).coerceIn(0.0, 255.0)
            }
        }
        // Forward rotation: raw (x, y) lands at upright position below (clockwise turn).
        val uw = if (rotation % 180 == 90) h else w
        val uh = if (rotation % 180 == 90) w else h
        val up = Array(3) { DoubleArray(uw * uh) }
        for (yR in 0 until h) {
            for (xR in 0 until w) {
                val (ux, uy) = when (rotation) {
                    0 -> xR to yR
                    90 -> (h - 1 - yR) to xR
                    180 -> (w - 1 - xR) to (h - 1 - yR)
                    else -> yR to (w - 1 - xR)
                }
                for (c in 0..2) up[c][uy * uw + ux] = rgb[c][yR * w + xR]
            }
        }
        val scale = min(size.toDouble() / uw, size.toDouble() / uh)
        val sw = (uw * scale).roundToInt().coerceIn(1, size)
        val sh = (uh * scale).roundToInt().coerceIn(1, size)
        val px = (size - sw) / 2
        val py = (size - sh) / 2
        val out = DoubleArray(3 * size * size) { 114.0 / 255.0 }
        for (oy in 0 until sh) {
            val sy = ((oy + 0.5) * uh / sh - 0.5).coerceIn(0.0, uh - 1.0)
            val ya = floor(sy).toInt()
            val yb = min(ya + 1, uh - 1)
            val fy = sy - ya
            for (ox in 0 until sw) {
                val sx = ((ox + 0.5) * uw / sw - 0.5).coerceIn(0.0, uw - 1.0)
                val xa = floor(sx).toInt()
                val xb = min(xa + 1, uw - 1)
                val fx = sx - xa
                for (c in 0..2) {
                    val v = up[c][ya * uw + xa] * (1 - fx) * (1 - fy) + up[c][ya * uw + xb] * fx * (1 - fy) +
                        up[c][yb * uw + xa] * (1 - fx) * fy + up[c][yb * uw + xb] * fx * fy
                    out[c * size * size + (oy + py) * size + ox + px] = v / 255.0
                }
            }
        }
        return out
    }

    @Test
    fun preprocess_reference_test() {
        val report = StringBuilder()
        for (spec in frames) {
            val yuv = makeYuv(spec)
            val got = FramePreprocessor.preprocess(yuv, spec.rotation, size)
            val ref = reference(yuv, spec.rotation, size)
            assertArrayEquals(longArrayOf(1, 3, size.toLong(), size.toLong()), got.shape)
            assertEquals(3 * size * size, got.tensor.size)
            var maxDiff = 0.0
            for (i in ref.indices) maxDiff = maxOf(maxDiff, abs(got.tensor[i] - ref[i]))
            // Fixed-point vs double YUV maths differ by at most ~1 level of 255.
            assertTrue("${spec.name}: max diff $maxDiff", maxDiff <= 2.0 / 255.0)
            assertTrue(got.tensor.all { it in 0f..1f })
            report.append(
                "${spec.name}: ${spec.w}x${spec.h} rot ${spec.rotation} -> upright ${got.uprightWidth}x${got.uprightHeight}, "
            )
                .append(
                    "scale %.4f pad (%.0f, %.0f), max diff vs reference %.5f\n".format(
                        got.scale,
                        got.padX,
                        got.padY,
                        maxDiff
                    )
                )
        }
        write("preprocess-reference.txt", report.toString())
    }

    @Test
    fun yuvConversionMatchesKnownColours() {
        val colours =
            listOf(
                Triple(255, 0, 0),
                Triple(0, 255, 0),
                Triple(0, 0, 255),
                Triple(128, 128, 128),
                Triple(255, 255, 255),
                Triple(0, 0, 0)
            )
        for ((r, g, b) in colours) {
            val y = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt()
            val u = (-0.168736 * r - 0.331264 * g + 0.5 * b + 128).roundToInt().coerceIn(0, 255)
            val v = (0.5 * r - 0.418688 * g - 0.081312 * b + 128).roundToInt().coerceIn(0, 255)
            val planes = YuvPlanes(2, 2, ByteArray(4) { y.toByte() }, byteArrayOf(u.toByte()), byteArrayOf(v.toByte()))
            val px = FramePreprocessor.yuvToArgb(planes)[0]
            val got = Triple((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
            assertTrue(
                "($r,$g,$b) -> $got",
                abs(got.first - r) <= 3 && abs(got.second - g) <= 3 && abs(got.third - b) <= 3
            )
        }
    }

    @Test
    fun handlesRotationMarkerEndsUpInTheRightCorner() {
        // 4x2 raw frame, red marker at raw top-left, rest black. Input 4 keeps the maths exact-ish.
        val w = 4
        val h = 2
        val argb = IntArray(w * h) { 0xFF000000.toInt() }
        argb[0] = 0xFFFF0000.toInt()
        fun redAt(p: Preprocessed, x: Int, y: Int) = p.tensor[y * p.inputSize + x]
        // rotation 90 clockwise: raw top-left goes to upright top-right. Upright is 2x4; size 4 -> no scaling.
        val r90 = FramePreprocessor.preprocess(argb, w, h, 90, 4)
        assertEquals(2, r90.uprightWidth)
        assertEquals(4, r90.uprightHeight)
        assertEquals(1f, redAt(r90, r90.padX.toInt() + 1, 0), 1e-6f)
        val r180 = FramePreprocessor.preprocess(argb, w, h, 180, 4)
        assertEquals(1f, redAt(r180, 3, r180.padY.toInt() + 1), 1e-6f)
        val r270 = FramePreprocessor.preprocess(argb, w, h, 270, 4)
        assertEquals(1f, redAt(r270, r270.padX.toInt(), 3), 1e-6f)
        val r0 = FramePreprocessor.preprocess(argb, w, h, -360, 4)
        assertEquals(1f, redAt(r0, 0, r0.padY.toInt()), 1e-6f)
    }

    @Test
    fun paddingIsGrey114AndStridesAreHonoured() {
        // Wide frame -> bars top and bottom.
        val p = FramePreprocessor.preprocess(IntArray(64 * 16) { 0xFFFFFFFF.toInt() }, 64, 16, 0, 64)
        assertEquals(114f / 255f, p.tensor[0], 1e-6f)
        assertEquals(1f, p.tensor[32 * 64 + 32], 1e-6f)
        // Row stride wider than the image (CameraX pads rows): padding bytes must be ignored.
        val y = ByteArray(8 * 2) { if (it % 8 < 4) 200.toByte() else 0 }
        val planes =
            YuvPlanes(
                4,
                2,
                y,
                ByteArray(8) {
                    128.toByte()
                },
                ByteArray(8) { 128.toByte() },
                yRowStride = 8,
                uvRowStride = 8,
                uvPixelStride = 2
            )
        val argb = FramePreprocessor.yuvToArgb(planes)
        assertTrue(argb.all { (it and 0xFF) == 200 })
    }

    @Test
    fun under8MsPerFrameOnLaptopJvm() {
        val yuv = makeYuv(Spec("vga", 640, 480, 90, "noise"))
        val argb = IntArray(640 * 480)
        val out = FloatArray(3 * size * size)
        repeat(30) { FramePreprocessor.preprocess(FramePreprocessor.yuvToArgb(yuv, argb), 640, 480, 90, size, out) }
        val times = (1..60).map {
            val t0 = System.nanoTime()
            FramePreprocessor.preprocess(FramePreprocessor.yuvToArgb(yuv, argb), 640, 480, 90, size, out)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val median = times[times.size / 2]
        val p95 = times[(times.size * 95) / 100]
        write(
            "preprocess-timing.txt",
            (
                "640x480 YUV, rot 90 -> 1x3x$size x$size; 60 runs after 30 warm-up\n" +
                    "median %.2f ms, p95 %.2f ms, max %.2f ms\n"
                ).format(
                median,
                p95,
                times.last()
            )
        )
        // Acceptance criterion is under 8 ms on a laptop JVM. Shared CI runners are slower and noisy,
        // so CI only checks a loose bound; the real number is in the timing log.
        val limit = if (System.getenv("CI") == "true") 40.0 else 8.0
        assertTrue("median $median ms > $limit ms", median < limit)
    }

    private fun write(name: String, text: String) {
        val f = File("build/reports/$name")
        f.parentFile.mkdirs()
        f.writeText(text)
    }
}
