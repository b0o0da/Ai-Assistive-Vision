package avn.engine

import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Level
import avn.contracts.Side
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.scenario.DecisionLog
import avn.engine.scenario.ScenarioLoader
import avn.engine.scenario.ScenarioRunner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notion test names: test_duplicate_suppressed, test_cooldown_expiry (WS16-05). */
class CooldownGateTest {
    private val clock = FakeClock()
    private val gate = CooldownGate(clock, CooldownConfig.load())

    private fun chair(band: DistanceBand = DistanceBand.NEAR, side: Side = Side.CENTER, label: String = "chair") =
        Event("evt_x", Source.CAMERA, EventType.OBSTACLE_AHEAD, label, side, band, 0.9, 54, 0L)

    @Test
    fun configOnClasspathIsMarkedProposedWithSpecValues() {
        val loaded = CooldownConfig.load()
        assertEquals("PROPOSED", loaded.status)
        assertEquals(3_000L, loaded.cooldownMsFor(Level.CRITICAL))
        assertEquals(8_000L, loaded.cooldownMsFor(Level.WARNING))
        assertEquals(30_000L, loaded.cooldownMsFor(Level.INFO))
        assertEquals(CooldownConfig.DEFAULT, CooldownConfig.load("/config/missing.json"))
    }

    @Test
    fun test_duplicate_suppressed() {
        // Inside the cooldown: same type + label + side + band is held back.
        assertEquals(GateResult.Pass, gate.check(chair(), Level.WARNING))
        clock.advanceBy(2_000)
        assertEquals(GateResult.Suppressed(Reason.COOLDOWN), gate.check(chair(), Level.WARNING))
        // Same key already speaking or queued: duplicate, whatever the timer says.
        val other = chair(label = "table")
        assertEquals(
            GateResult.Suppressed(Reason.DUPLICATE),
            gate.check(other, Level.WARNING, activeKeys = setOf(DedupKey.of(other)))
        )
        // Label case and spaces don't make a new key.
        assertEquals(GateResult.Suppressed(Reason.COOLDOWN), gate.check(chair(label = " Chair "), Level.WARNING))
    }

    @Test
    fun test_cooldown_expiry() {
        for ((level, ms) in listOf(Level.CRITICAL to 3_000L, Level.WARNING to 8_000L, Level.INFO to 30_000L)) {
            val g = CooldownGate(clock)
            val e = chair()
            assertEquals(GateResult.Pass, g.check(e, level))
            clock.advanceBy(ms - 1)
            assertEquals("$level just before", GateResult.Suppressed(Reason.COOLDOWN), g.check(e, level))
            clock.advanceBy(1)
            assertEquals("$level at expiry", GateResult.Pass, g.check(e, level))
        }
    }

    @Test
    fun bandOrSideChangeBypassesDedup() {
        assertEquals(GateResult.Pass, gate.check(chair(DistanceBand.FAR), Level.WARNING))
        clock.advanceBy(100)
        assertEquals(GateResult.Pass, gate.check(chair(DistanceBand.MEDIUM), Level.WARNING))
        clock.advanceBy(100)
        assertEquals(GateResult.Pass, gate.check(chair(DistanceBand.NEAR), Level.WARNING))
        clock.advanceBy(100)
        assertEquals(GateResult.Pass, gate.check(chair(DistanceBand.NEAR, Side.LEFT), Level.WARNING))
    }

    @Test
    fun escalationToCriticalIsNeverHeldByLowerCooldown() {
        assertEquals(GateResult.Pass, gate.check(chair(), Level.WARNING))
        clock.advanceBy(500)
        assertEquals(GateResult.Pass, gate.check(chair(), Level.CRITICAL))
        clock.advanceBy(500)
        // Now it is a Critical inside the Critical cooldown.
        assertEquals(GateResult.Suppressed(Reason.COOLDOWN), gate.check(chair(), Level.CRITICAL))
    }

    @Test
    fun suppressedEventsDoNotRestartTheTimerAndOldKeysArePruned() {
        assertEquals(GateResult.Pass, gate.check(chair(), Level.CRITICAL))
        repeat(5) {
            clock.advanceBy(500)
            gate.check(chair(), Level.CRITICAL)
        }
        clock.advanceBy(500) // 3000 ms after the first pass
        assertEquals(GateResult.Pass, gate.check(chair(), Level.CRITICAL))
        clock.advanceBy(31_000)
        gate.check(chair(label = "bench"), Level.INFO)
        assertEquals(1, gate.trackedKeys)
    }

    /** Test-only engine: risk -> level, then the gate. Real level rules are WS16-03 (week 4). */
    private class GateEngine(private val clock: Clock, private val log: DecisionLog) : EngineInput {
        private val gate = CooldownGate(clock)

        override fun submit(event: Event) {
            val level = when {
                event.risk >= 80 -> Level.CRITICAL
                event.risk >= 45 -> Level.WARNING
                else -> Level.INFO
            }
            when (val r = gate.check(event, level)) {
                GateResult.Pass -> log.record(event.id, Decision.SPOKEN, null)
                is GateResult.Suppressed -> log.record(event.id, Decision.SUPPRESSED, r.reason)
            }
        }

        override fun submit(candidate: SpeechCandidate) = Unit
    }

    @Test
    fun cooldownScenarioReplaysWithVirtualClock() {
        val scenario = ScenarioLoader.parse(javaClass.getResource("/scenarios/cooldown_dedup.json")!!.readText())
        val report = ScenarioRunner { c, log -> GateEngine(c, log) }.run(scenario)
        val out = File("build/reports/cooldown-scenario-log.jsonl")
        out.parentFile.mkdirs()
        out.writeText(report.logLines().joinToString("\n") + "\n")
        assertTrue(report.mismatches.joinToString("\n"), report.passed)
        assertTrue(report.rejectedByIntake.isEmpty())
        assertEquals(12, report.log.size)
    }
}
