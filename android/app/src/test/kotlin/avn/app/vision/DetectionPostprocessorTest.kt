package avn.app.vision

import avn.app.camera.Frame
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WS05-07 (Notion test names: class_filter_test, threshold_test; NMS on 3 fixtures). */
class DetectionPostprocessorTest {
    private val raw = File(System.getProperty("avn.repoRoot"), "tests/fixtures/detections_raw")
    private val post = DetectionPostprocessor(PostprocessConfig.load())

    private fun det(label: String, c: Float, x: Float = 10f, y: Float = 10f, w: Float = 100f, h: Float = 100f) =
        Detection(label, c, x, y, w, h, 0L)

    @Test
    fun configIsProposedAndMatchesDefault() {
        val text = File(
            System.getProperty("avn.repoRoot"),
            "android/app/src/main/resources/config/postprocess.json"
        ).readText()
        assertTrue(text.contains("\"status\": \"PROPOSED\""))
        assertEquals(PostprocessConfig.DEFAULT, PostprocessConfig.load())
        assertEquals(PostprocessConfig.DEFAULT, PostprocessConfig.load("/config/missing.json"))
    }

    @Test
    fun class_filter_test() {
        val out = post.apply(listOf(det("cat", 0.99f), det("tv", 0.9f), det("Car", 0.8f), det("chair", 0.8f, x = 500f)))
        assertEquals(listOf("transportation", "chair"), out.map { it.label })
    }

    @Test
    fun threshold_test() {
        // default 0.35, person 0.40, pothole 0.30 (config/postprocess.json).
        val out = post.apply(
            listOf(
                det("person", 0.39f),
                det("person", 0.41f, x = 500f),
                det("chair", 0.34f, x = 900f),
                det("chair", 0.36f, x = 1200f),
                det("pothole", 0.31f, x = 1500f)
            )
        )
        assertEquals(
            listOf("person" to 0.41f, "chair" to 0.36f, "pothole" to 0.31f),
            out.map {
                it.label to
                    it.confidence
            }
        )
        val strict = DetectionPostprocessor(PostprocessConfig.DEFAULT.copy(minConfidence = mapOf("default" to 0.9f)))
        assertTrue(strict.apply(listOf(det("chair", 0.8f))).isEmpty())
    }

    @Test
    fun nmsRemovesDuplicatesInTheThreeRawFixtures() {
        val files = raw.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertEquals(3, files.size)
        for (f in files) {
            val root = Json.parseToJsonElement(f.readText()).jsonObject
            val input = root.getValue("detections").jsonArray.map { e ->
                val o = e.jsonObject
                Detection(
                    o.getValue("label").jsonPrimitive.content,
                    o.getValue("confidence").jsonPrimitive.float,
                    o.getValue("x").jsonPrimitive.float,
                    o.getValue("y").jsonPrimitive.float,
                    o.getValue("w").jsonPrimitive.float,
                    o.getValue("h").jsonPrimitive.float,
                    0L
                )
            }
            val expected = root.getValue("expected").jsonArray.map { e ->
                e.jsonObject.getValue("label").jsonPrimitive.content to
                    e.jsonObject.getValue("confidence").jsonPrimitive.float
            }
            assertEquals(f.name, expected, post.apply(input).map { it.label to it.confidence })
        }
    }

    @Test
    fun differentClassesAreNeverSuppressedByEachOtherAndOutputIsCapped() {
        val same = det("person", 0.9f)
        assertEquals(2, post.apply(listOf(same, same.copy(label = "chair", confidence = 0.8f))).size)
        val many = (0 until 80).map { det("chair", 0.5f + it / 1000f, x = it * 200f) }
        assertEquals(50, post.apply(many).size)
    }

    @Test
    fun wrapperAppliesPostprocessingToAnyDetector() = runBlocking {
        val inner = object : Detector {
            override suspend fun detect(frame: Frame) =
                listOf(det("cat", 0.9f), det("person", 0.9f), det("person", 0.8f))
        }
        val out = PostprocessingDetector(inner, post).detect(Frame(null, 0, 0))
        assertEquals(listOf("person" to 0.9f), out.map { it.label to it.confidence })
    }
}
