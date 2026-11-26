package avn.app.screen

import avn.contracts.ScreenFlags
import avn.contracts.ScreenSnapshot
import avn.contracts.ScreenType
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WS17-05 (Notion test name: TextNormalizerGoldenTest). Evidence: before/after table of every fixture. */
class TextNormalizerGoldenTest {
    private val dir = File(System.getProperty("avn.repoRoot"), "tests/fixtures/screens")
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun goldenOutputsMatchOnAllFixtures() {
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertEquals(5, files.size)
        val table = StringBuilder("| fixture | node texts in (before) | spoken lines out (after) |\n|---|---|---|\n")
        for (f in files) {
            val root = json.parseToJsonElement(f.readText()).jsonObject
            val snapshot = json.decodeFromJsonElement(ScreenSnapshot.serializer(), root.getValue("snapshot"))
            val expected = root.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
            val actual = TextNormalizer.normalize(snapshot)
            assertEquals(f.name, expected, actual)
            val before = snapshot.nodes.joinToString(" / ") { it.text.ifBlank { "[${it.contentDescription}]" } }
            table.append("| ${f.nameWithoutExtension} | $before | ${actual.joinToString(" / ")} |\n")
        }
        File("build/reports/text-normalizer-before-after.md").apply { parentFile.mkdirs() }.writeText(table.toString())
    }

    @Test
    fun cleanRules() {
        assertEquals("5 minutes ago", TextNormalizer.clean("5m"))
        assertEquals("1 hour ago", TextNormalizer.clean("1h"))
        assertEquals("2 days ago", TextNormalizer.clean("2d"))
        assertEquals("10:42", TextNormalizer.clean("10:42"))
        assertEquals("4 unread", TextNormalizer.clean("4", "row_unread_count"))
        assertEquals("4", TextNormalizer.clean("4", "price"))
        assertNull(TextNormalizer.clean("More options"))
        assertNull(TextNormalizer.clean("​ · ​"))
        assertEquals("Nice", TextNormalizer.clean("Nice 🦄"))
        assertEquals("مرحبا", TextNormalizer.clean("‏مرحبا‎"))
    }

    @Test
    fun secureScreensAndPasswordNodesAreNeverRead() {
        val node = avn.contracts.NodeInfo("1234", clickable = false, scrollable = false, isPassword = true)
        val open = ScreenSnapshot("pkg", ScreenType.OTHER, listOf(node), "", emptyList(), ScreenFlags())
        assertTrue(TextNormalizer.normalize(open).isEmpty())
        val secure = open.copy(nodes = listOf(node.copy(isPassword = false)), flags = ScreenFlags(secure = true))
        assertTrue(TextNormalizer.normalize(secure).isEmpty())
    }

    @Test
    fun speechJoinsLinesWithoutDoublePunctuation() {
        assertEquals("Ahmed. Are you coming? Yes", TextNormalizer.toSpeech(listOf("Ahmed.", "Are you coming?", "Yes")))
        assertEquals("", TextNormalizer.toSpeech(emptyList()))
    }
}
