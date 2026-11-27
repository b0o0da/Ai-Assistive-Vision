package avn.app.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WS07-03 on a device (emulator or phone): the real ML Kit bundled Latin model, no network needed.
 * Run: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=avn.app.vision.MlKitOnDeviceTest
 * Latency lines are written to logcat with tag AVN_PERF (numbers only, never text).
 */
@RunWith(AndroidJUnit4::class)
class MlKitOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine =
        MlKitOcrEngine(MlKitTextRecognizer(), JsonLinesLogger(context), perf = null, config = OcrConfig.load())

    /** A sign drawn in code: black text on white, like the sign_pharmacy fixture. */
    private fun sign(rotate: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 150f
            typeface = Typeface.DEFAULT_BOLD
        }
        val small = Paint(big).apply {
            textSize = 80f
            typeface = Typeface.DEFAULT
        }
        c.drawText("PHARMACY", 180f, 300f, big)
        c.drawText("Open until 10 PM", 260f, 480f, small)
        if (!rotate) return bmp
        val m = android.graphics.Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    @Test
    fun readsADrawnSignOnDevice() = runBlocking {
        val reading = engine.read(Frame(sign(), 0, 0))
        val text = reading.text.uppercase()
        assertTrue("got: $text", text.contains("PHARMACY"))
        assertTrue("got: $text", text.contains("10 PM"))
        assertEquals("mlkit", reading.source)
        assertTrue(
            reading.blocks.indexOfFirst { it.text.uppercase().contains("PHARMACY") } <
                reading.blocks.indexOfFirst { it.text.uppercase().contains("10 PM") }
        )
    }

    @Test
    fun rotationFromTheCameraIsHonoured() = runBlocking {
        // The frame arrives turned 90 degrees, as from a sideways sensor; rotationDegrees turns it back.
        val reading = engine.read(Frame(sign(rotate = true), 90, 0))
        assertTrue("got: ${reading.text}", reading.text.uppercase().contains("PHARMACY"))
    }

    @Test
    fun blankFrameGivesAClearNoTextResult() = runBlocking {
        val blank = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertEquals(NoTextReason.BLURRY, engine.read(Frame(blank, 0, 0)).noText)
        assertEquals(NoTextReason.EMPTY_FRAME, engine.read(Frame(null, 0, 0)).noText)
    }

    @Test
    fun latencyLog() = runBlocking {
        val bmp = sign()
        repeat(3) { engine.read(Frame(bmp, 0, 0)) } // warm-up: first call loads the model
        val times = (1..20).map {
            val t0 = System.nanoTime()
            engine.read(Frame(bmp, 0, 0))
            (System.nanoTime() - t0) / 1_000_000.0
        }.sorted()
        val median = times[times.size / 2]
        val p95 = times[(times.size * 95) / 100]
        Log.i("AVN_PERF", "mlkit_read_ms 1280x720 n=20 median=%.1f p95=%.1f max=%.1f".format(median, p95, times.last()))
        assertTrue("median $median ms", median < 5_000)
    }
}
