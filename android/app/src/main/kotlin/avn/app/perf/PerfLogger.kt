package avn.app.perf

import avn.app.core.logging.JsonLinesLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * architecture.md WS-27: "APIs / interfaces: PerfLogger.record(metric)". Reuses the same
 * content-free [JsonLinesLogger] every other module already writes to (one line per
 * request/event chain) instead of a second logging path -- a timing value is never content, so
 * there is nothing for that logger's deny-list to redact.
 *
 * This is per-module latency timing only. Thermal/battery/FPS-governor logging and the 30-minute
 * stable-session report architecture.md also lists for WS-27 need a real physical device over a
 * real 30-minute run, which this repo does not have -- tracked as a separate, open gap rather
 * than invented here.
 */
@Singleton
class PerfLogger @Inject constructor(private val log: JsonLinesLogger) {
    fun record(module: String, event: String, durationMs: Long) {
        log.log(module, "perf_$event", fields = mapOf("ms" to durationMs))
    }

    inline fun <T> measure(module: String, event: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        record(module, event, (System.nanoTime() - start) / 1_000_000)
        return result
    }

    suspend inline fun <T> measureSuspend(module: String, event: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        val result = block()
        record(module, event, (System.nanoTime() - start) / 1_000_000)
        return result
    }
}
