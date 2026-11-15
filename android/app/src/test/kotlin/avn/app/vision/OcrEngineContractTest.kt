package avn.app.vision

import android.content.Context
import avn.app.camera.Frame
import avn.app.core.logging.JsonLinesLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Notion test name: test_ocr_engine_contract (WS07-01). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrEngineContractTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val frame = Frame(bitmap = null, rotationDegrees = 0, timestampMs = 0)

    /** Finds a file under the repo root, whatever folder Gradle runs the tests from. */
    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.exists() }
            ?: error("could not find $path above ${File("").absolutePath}")

    private fun fixture(name: String): OcrReading =
        OcrFixtures.parse(repoFile("tests/fixtures/ocr/$name").readText())

    /** Rules every OcrEngine (fake now, ML Kit later) must follow. */
    private fun assertObeysContract(reading: OcrReading) {
        reading.blocks.forEach { block ->
            assertTrue("blank block text", block.text.isNotBlank())
            assertTrue("confidence out of range", block.confidence in 0.0..1.0)
            assertTrue("negative box size", block.box.w >= 0 && block.box.h >= 0)
        }
        val result = reading.toResult()
        assertEquals(reading.text, result.text)
        assertTrue("result confidence out of range", result.confidence in 0.0..1.0)
        assertTrue("language must be set", reading.language.isNotBlank())
    }

    @Test
    fun fakeReplaysFixturesInOrderThenReturnsEmpty() = runBlocking {
        val engine = FakeOcrEngine(JsonLinesLogger(context))
        engine.enqueue(fixture("sign_pharmacy.json"))
        engine.enqueue(fixture("blank_wall.json"))

        val first = engine.read(frame)
        assertObeysContract(first)
        assertEquals(listOf("PHARMACY", "Open until 10 PM"), first.blocks.map { it.text })
        assertEquals("PHARMACY\nOpen until 10 PM", first.text)
        assertEquals(TextBox(120f, 80f, 400f, 90f), first.blocks[0].box)

        val second = engine.read(frame)
        assertObeysContract(second)
        assertTrue(second.isEmpty)

        val afterQueue = engine.read(frame)
        assertObeysContract(afterQueue)
        assertTrue(afterQueue.isEmpty)
        assertEquals(FakeOcrEngine.SOURCE, afterQueue.source)
    }

    @Test
    fun resultConfidenceIsTheBlockAverage() {
        val result = fixture("sign_pharmacy.json").toResult()
        assertEquals(0.94, result.confidence, 1e-9)
        assertEquals(0.0, OcrReading.empty("fake").toResult().confidence, 0.0)
    }

    @Test
    fun badBlocksAreRejected() {
        val box = TextBox(0f, 0f, 10f, 10f)
        assertThrows(IllegalArgumentException::class.java) { OcrBlock(" ", box, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { OcrBlock("a", box, 1.5) }
        assertThrows(IllegalArgumentException::class.java) { TextBox(0f, 0f, -1f, 10f) }
    }
}
