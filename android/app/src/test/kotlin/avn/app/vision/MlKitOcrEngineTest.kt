package avn.app.vision

import android.graphics.Bitmap
import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** WS07-03 (Notion test names: test_mlkit_fixture_text, test_ocr_empty_frame, test_ocr_offline). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlKitOcrEngineTest {
    private val repo = File(System.getProperty("avn.repoRoot"))
    private val log = JsonLinesLogger(RuntimeEnvironment.getApplication())

    /** Records calls; returns [lines] or throws [error]. Stands in for ML Kit on the laptop. */
    private class FakeRecognizer(var lines: List<RecognizedLine> = emptyList(), var error: Exception? = null) :
        TextRecognizerPort {
        var calls = 0

        override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<RecognizedLine> {
            calls++
            error?.let { throw it }
            return lines
        }
    }

    /** A sharp test card (checkerboard) and a flat one; pixels come from [gray] so no real ML Kit runs. */
    private fun checker(w: Int = 64, h: Int = 64) = Triple(
        IntArray(w * h) { i ->
            if (((i % w) / 4 + (i / w) / 4) % 2 ==
                0
            ) {
                0
            } else {
                255
            }
        },
        w,
        h
    )
    private fun flat(w: Int = 64, h: Int = 64) = Triple(IntArray(w * h) { 128 }, w, h)
    private fun gradient(w: Int = 64, h: Int = 64) = Triple(IntArray(w * h) { i -> (i % w) * 255 / (w - 1) }, w, h)

    private val bitmap: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private fun engine(rec: FakeRecognizer, pixels: Triple<IntArray, Int, Int> = checker()) =
        MlKitOcrEngine(rec, log, perf = null, config = OcrConfig.DEFAULT, gray = { _, _ -> pixels })

    @Test
    fun test_mlkit_fixture_text() = runBlocking {
        // The sign_pharmacy fixture as the recognizer would return it: unordered lines, ML Kit's box format.
        val fixture = OcrFixtures.parse(File(repo, "tests/fixtures/ocr/sign_pharmacy.json").readText())
        val lines = fixture.blocks.reversed().map {
            RecognizedLine(
                " ${it.text}  ",
                it.box.x.toInt(),
                it.box.y.toInt(),
                (it.box.x + it.box.w).toInt(),
                (
                    it.box.y +
                        it.box.h
                    ).toInt(),
                it.confidence.toFloat()
            )
        }
        val reading = engine(FakeRecognizer(lines)).read(Frame(bitmap, 90, 0))

        assertEquals(fixture.blocks.map { it.text }, reading.blocks.map { it.text })
        assertEquals(fixture.blocks.map { it.box }, reading.blocks.map { it.box })
        assertEquals("mlkit", reading.source)
        assertNull(reading.noText)
        assertEquals("PHARMACY\nOpen until 10 PM", reading.text)
    }

    @Test
    fun test_ocr_empty_frame() = runBlocking {
        val rec = FakeRecognizer(listOf(RecognizedLine("X", 0, 0, 10, 10, 0.9f)))
        assertEquals(NoTextReason.EMPTY_FRAME, engine(rec).read(Frame(null, 0, 0)).noText)
        assertEquals(NoTextReason.BLURRY, engine(rec, flat()).read(Frame(bitmap, 0, 0)).noText)
        assertEquals(NoTextReason.BLURRY, engine(rec, gradient()).read(Frame(bitmap, 0, 0)).noText)
        assertEquals("ML Kit is never called for an empty or blurry frame", 0, rec.calls)

        val nothing = engine(FakeRecognizer(emptyList())).read(Frame(bitmap, 0, 0))
        assertTrue(nothing.isEmpty)
        assertEquals(NoTextReason.NO_TEXT, nothing.noText)
        assertEquals("I can't see any text.", nothing.noText!!.spoken)
    }

    @Test
    fun lowConfidenceAndBlankLinesAreDroppedAndConfidenceIsClamped() = runBlocking {
        val lines = listOf(
            RecognizedLine("noise", 0, 0, 50, 10, 0.2f),
            RecognizedLine("   ", 0, 20, 50, 30, 0.9f),
            RecognizedLine("EXIT", 0, 40, 50, 60, null),
            RecognizedLine("zero box", 5, 5, 5, 5, 0.9f)
        )
        val reading = engine(FakeRecognizer(lines)).read(Frame(bitmap, 0, 0))
        assertEquals(listOf("EXIT"), reading.blocks.map { it.text })
        assertEquals(1.0, reading.blocks.single().confidence, 1e-9)
    }

    @Test
    fun recognizerFailureIsASpokenNoTextResultNotACrash() = runBlocking {
        val reading = engine(FakeRecognizer(error = IllegalStateException("model not ready"))).read(Frame(bitmap, 0, 0))
        assertEquals(NoTextReason.ENGINE_ERROR, reading.noText)
        assertFalse(reading.noText!!.spoken.isBlank())
    }

    @Test
    fun readingOrderIsTopToBottomThenLeftToRight() {
        val b = { t: String, x: Float, y: Float -> OcrBlock(t, TextBox(x, y, 80f, 20f), 0.9) }
        val ordered = OcrLineMapper.readingOrder(
            listOf(b("right", 200f, 102f), b("bottom", 0f, 200f), b("left", 0f, 100f), b("top", 50f, 10f))
        )
        assertEquals(listOf("top", "left", "right", "bottom"), ordered.map { it.text })
    }

    @Test
    fun laplacianVarianceSeparatesSharpFromFlat() {
        val (c, w, h) = checker()
        val (f, fw, fh) = flat()
        assertTrue(Sharpness.laplacianVariance(c, w, h) > 1_000.0)
        assertEquals(0.0, Sharpness.laplacianVariance(f, fw, fh), 1e-9)
        assertEquals(0.0, Sharpness.laplacianVariance(IntArray(4), 2, 2), 1e-9)
    }

    /** Offline by construction: the bundled model artifact, never the Play-services (download-on-demand) one. */
    @Test
    fun test_ocr_offline() {
        val catalog = File(repo, "android/gradle/libs.versions.toml").readText()
        assertTrue(catalog.contains("module = \"com.google.mlkit:text-recognition\""))
        assertFalse(catalog.contains("play-services-mlkit-text-recognition"))
        val source = File(repo, "android/app/src/main/kotlin/avn/app/vision/MlKitOcrEngine.kt").readText()
        assertTrue(source.contains("TextRecognizerOptions.DEFAULT_OPTIONS"))
        assertEquals(
            "PROPOSED",
            File(repo, "android/app/src/main/resources/config/ocr.json").readText().let {
                Regex("\"status\": \"(\\w+)\"").find(it)!!.groupValues[1]
            }
        )
    }

    @Test
    fun grayscaleDownscalesRealBitmaps() {
        val big = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        val (g, w, h) = Sharpness.grayOf(big, 256)
        assertEquals(256, w)
        assertEquals(128, h)
        assertEquals(w * h, g.size)
    }
}
