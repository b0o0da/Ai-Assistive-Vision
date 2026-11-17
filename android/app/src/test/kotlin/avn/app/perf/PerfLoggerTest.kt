package avn.app.perf

import android.content.Context
import avn.app.core.logging.JsonLinesLogger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * WS-27 (architecture.md: "APIs / interfaces: PerfLogger.record(metric)"). [JsonLinesLogger] writes
 * off a background executor, so these tests poll the real log file briefly rather than assume the
 * write already landed by the time [JsonLinesLogger.log] returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfLoggerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val logFile = File(context.filesDir, "logs/avn.jsonl")

    private fun awaitLoggedLine(): String {
        repeat(50) {
            if (logFile.exists() && logFile.length() > 0) return logFile.readText()
            Thread.sleep(20)
        }
        error("no log line written within timeout")
    }

    @Test
    fun recordWritesAPerfPrefixedEventWithTheDurationInMs() {
        val perf = PerfLogger(JsonLinesLogger(context))
        perf.record("tracker", "update_ms", 7L)

        val line = awaitLoggedLine()
        assertTrue(line.contains("\"module\":\"tracker\""))
        assertTrue(line.contains("\"event\":\"perf_update_ms\""))
        assertTrue(line.contains("\"ms\":7"))
    }

    @Test
    fun measureRecordsADurationAndStillReturnsTheBlockResult() {
        val perf = PerfLogger(JsonLinesLogger(context))
        val result = perf.measure("detector", "inference_ms") { 1 + 1 }
        assertEquals(2, result)

        val line = awaitLoggedLine()
        assertTrue(line.contains("\"module\":\"detector\""))
        assertTrue(line.contains("\"event\":\"perf_inference_ms\""))
    }

    @Test
    fun measureSuspendRecordsADurationAndStillReturnsTheBlockResult() = kotlinx.coroutines.runBlocking {
        val perf = PerfLogger(JsonLinesLogger(context))
        val result = perf.measureSuspend("ocr", "read_ms") { 21 + 21 }
        assertEquals(42, result)

        val line = awaitLoggedLine()
        assertTrue(line.contains("\"module\":\"ocr\""))
        assertTrue(line.contains("\"event\":\"perf_read_ms\""))
    }
}
