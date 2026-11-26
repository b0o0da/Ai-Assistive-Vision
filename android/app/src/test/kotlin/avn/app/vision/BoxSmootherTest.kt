package avn.app.vision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WS06-03 (Notion test name: smoother_test). Evidence: before/after plot of the jitter fixture. */
class BoxSmootherTest {
    private val repo = File(System.getProperty("avn.repoRoot"))
    private val jitter = DetectionSequences.parse(
        File(repo, "tests/fixtures/detections_jitter/chair_jitter.json").readText()
    )

    private fun variance(xs: List<Float>): Double {
        val m = xs.average()
        return xs.sumOf { (it - m) * (it - m) } / xs.size
    }

    private fun flips(bands: List<Any>) = bands.zipWithNext().count { (a, b) -> a != b }

    @Test
    fun configIsProposedWithLagUnderTwoFrames() {
        val text = File(repo, "android/app/src/main/resources/config/smoothing.json").readText()
        assertTrue(text.contains("\"status\": \"PROPOSED\""))
        assertEquals(SmoothingConfig.DEFAULT, SmoothingConfig.load())
        assertTrue(SmoothingConfig.load().lagFrames < 2f)
    }

    @Test
    fun smoother_test_varianceAndBandFlipsDropOnJitterFixture() {
        val smoother = BoxSmoother()
        val bander = DistanceBander()
        val raw = jitter.frames.map { it.detections.single() }
        val smooth = raw.map { smoother.smooth(1, it) }
        val fh = jitter.frameHeight.toFloat()

        val rawH = variance(raw.map { it.h })
        val smH = variance(smooth.map { it.h })
        val rawBottom = variance(raw.map { it.y + it.h })
        val smBottom = variance(smooth.map { it.y + it.h })
        val rawFlips = flips(raw.map { bander.estimate(it, fh) })
        val smFlips = flips(smooth.map { bander.estimate(it, fh) })

        assertTrue("height variance $smH vs $rawH", smH < 0.5 * rawH)
        assertTrue("bottom variance $smBottom vs $rawBottom", smBottom < 0.5 * rawBottom)
        assertTrue("band flips $smFlips vs $rawFlips", smFlips < rawFlips)

        writeEvidence(raw, smooth, rawFlips, smFlips, rawH, smH)
    }

    @Test
    fun lagOnConstantMotionIsUnderTwoFrames() {
        val smoother = BoxSmoother()
        val speed = 8f // px per frame, small enough to never trigger the reset
        var lag = 0f
        for (i in 0 until 40) {
            val raw = Detection("person", 0.9f, 100f + i * speed, 500f, 200f, 400f, i * 100L)
            val s = smoother.smooth(7, raw)
            lag = (raw.x - s.x) / speed
        }
        assertEquals(1f, lag, 0.01f)
        assertTrue(lag < 2f)
    }

    @Test
    fun aBigJumpSnapsInsteadOfAveraging() {
        val smoother = BoxSmoother()
        smoother.smooth(1, Detection("car", 0.9f, 0f, 0f, 100f, 100f, 0))
        val jumped = Detection("car", 0.9f, 600f, 600f, 300f, 300f, 100)
        assertEquals(jumped, smoother.smooth(1, jumped))
    }

    @Test
    fun tracksAreSmoothedIndependentlyAndForgotten() {
        val smoother = BoxSmoother()
        fun track(id: Int, x: Float) =
            Track(id, "chair", emptyList(), Detection("chair", 0.9f, x, 0f, 100f, 100f, 0), 1, 0)
        smoother.smoothTracks(listOf(track(1, 0f), track(2, 500f)))
        val out = smoother.smoothTracks(listOf(track(1, 10f), track(2, 510f)))
        assertEquals(5f, out[0].lastBox.x, 1e-4f)
        assertEquals(505f, out[1].lastBox.x, 1e-4f)
        repeat(10) { smoother.smoothTracks(listOf(track(1, 10f))) }
        assertEquals(1, smoother.size)
    }

    private fun writeEvidence(
        raw: List<Detection>,
        smooth: List<Detection>,
        rawFlips: Int,
        smFlips: Int,
        rawVar: Double,
        smVar: Double
    ) {
        val dir = File("build/reports").apply { mkdirs() }
        File(dir, "smoothing-before-after.csv").writeText(
            "frame,raw_h,smoothed_h,raw_bottom,smoothed_bottom\n" +
                raw.indices.joinToString("\n") { i ->
                    "$i,${raw[i].h},${smooth[i].h},${raw[i].y + raw[i].h},${smooth[i].y + smooth[i].h}"
                } + "\n"
        )
        // Simple SVG line plot of box height, raw vs smoothed, with the near line (0.30 x 1920 = 576 px).
        val w = 640
        val h = 260
        val lo = 480f
        val hi = 640f
        fun px(i: Int) = 40 + i * (w - 60) / (raw.size - 1)
        fun py(v: Float) = (h - 30) - ((v - lo) / (hi - lo) * (h - 50)).toInt()
        fun path(vs: List<Float>) =
            vs.mapIndexed { i, v -> "${if (i == 0) "M" else "L"}${px(i)},${py(v)}" }.joinToString(" ")
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" font-family="sans-serif" font-size="11">
            <rect width="$w" height="$h" fill="white"/>
            <line x1="40" x2="${w - 20}" y1="${py(576f)}" y2="${py(576f)}" stroke="#999" stroke-dasharray="4 3"/>
            <text x="44" y="${py(576f) - 4}" fill="#666">near line (576 px)</text>
            <path d="${path(raw.map { it.h })}" fill="none" stroke="#c0392b" stroke-width="1.2"/>
            <path d="${path(smooth.map { it.h })}" fill="none" stroke="#2c7be5" stroke-width="2"/>
            <text x="40" y="16" fill="#c0392b">raw box height: variance ${"%.0f".format(
            rawVar
        )}, band flips $rawFlips</text>
            <text x="340" y="16" fill="#2c7be5">smoothed: variance ${"%.0f".format(smVar)}, band flips $smFlips</text>
            <text x="40" y="${h - 8}" fill="#666">frame (10 fps, 60 frames) - chair_jitter fixture</text>
            </svg>
        """.trimIndent()
        File(dir, "smoothing-before-after.svg").writeText(svg)
    }
}
