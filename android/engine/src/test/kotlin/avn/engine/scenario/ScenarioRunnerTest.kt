package avn.engine.scenario

import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Level
import avn.contracts.SpeechCandidate
import avn.contracts.SpeechRequest
import avn.engine.Clock
import avn.engine.Decision
import avn.engine.DedupKey
import avn.engine.EngineInput
import avn.engine.FakeSpeaker
import avn.engine.InterruptRules
import avn.engine.Reason
import avn.engine.SpeechItem
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notion test name: test_runner_virtual_clock (WS16-02), plus the tracker fixture replay. */
class ScenarioRunnerTest {
    private fun resource(path: String) = javaClass.getResource(path)!!.readText()

    /** Stub engine with two rules, enough to check the runner itself: low confidence is dropped, the rest spoken. */
    private class StubEngine(private val clock: Clock, private val log: DecisionLog) : EngineInput {
        val seenAt = mutableListOf<Long>()
        val contexts = mutableListOf<List<Boolean>>()

        override fun submit(event: Event) {
            seenAt += clock.nowMillis()
            if (event.confidence < 0.3) {
                log.record(event.id, Decision.DROPPED, Reason.LOW_CONFIDENCE)
            } else {
                log.record(event.id, Decision.SPOKEN, null)
            }
        }

        override fun submit(candidate: SpeechCandidate) {
            seenAt += clock.nowMillis()
            log.record(candidate.eventId ?: "?", Decision.SPOKEN, null)
        }

        override fun updateContext(
            listening: Boolean,
            speaking: Boolean,
            moving: Boolean,
            navigationActive: Boolean,
            offline: Boolean,
            sosActive: Boolean
        ) {
            seenAt += clock.nowMillis()
            contexts += listOf(listening, speaking, moving, navigationActive, offline, sosActive)
        }
    }

    /**
     * Test-only adapter from EngineInput to the real InterruptRules (AVN-55). The level per event
     * type is a stand-in until WS16-03 risk scoring exists; it lives in the test on purpose.
     */
    private class InterruptRulesEngine(clock: Clock, private val log: DecisionLog) : EngineInput {
        val speaker = FakeSpeaker()
        private val rules = InterruptRules(speaker, clock)

        private fun levelOf(type: EventType) = when (type) {
            EventType.VEHICLE_APPROACHING -> Level.CRITICAL
            EventType.PERSON_CLOSE, EventType.OBSTACLE_AHEAD -> Level.WARNING
            else -> Level.INFO
        }

        override fun submit(event: Event) {
            val level = levelOf(event.type)
            val request = SpeechRequest(event.id, event.label, level, event.source, level != Level.CRITICAL, event.id)
            log.record(event.id, rules.submit(SpeechItem(request, dedupKey = DedupKey.of(event))), null)
        }

        override fun submit(candidate: SpeechCandidate) {
            val id = candidate.eventId ?: "cand"
            val request = SpeechRequest(id, candidate.text, Level.INFO, candidate.source, true, id)
            log.record(id, rules.submit(SpeechItem(request, userRequested = candidate.userRequested)), null)
        }

        override fun updateContext(
            listening: Boolean,
            speaking: Boolean,
            moving: Boolean,
            navigationActive: Boolean,
            offline: Boolean,
            sosActive: Boolean
        ) = rules.updateContext(listening, speaking, moving, navigationActive, offline, sosActive)
    }

    @Test
    fun engineSeesVirtualTimeOfEachStep() {
        var stub: StubEngine? = null
        val report = ScenarioRunner { clock, log -> StubEngine(clock, log).also { stub = it } }
            .run(ScenarioLoader.parse(resource("/scenarios/sample.json")))
        assertEquals(listOf(0L, 100L, 500L), stub!!.seenAt)
        assertTrue(report.mismatches.toString(), report.passed)
    }

    @Test
    fun tenMinuteScenarioReplaysWithoutWaiting() {
        val scenario = ScenarioLoader.parse(
            """{"name":"long","description":"d","steps":[
               {"at_ms":0,"context":{"moving":true}},
               {"at_ms":600000,"candidate":{"text":"hi","source":"voice","type":"scene_answer","event_id":"c1"}}],
               "expect":[{"event_id":"c1","decision":"spoken"}]}"""
        )
        val started = System.nanoTime()
        val report = ScenarioRunner { clock, log -> StubEngine(clock, log) }.run(scenario)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(report.passed)
        assertEquals(600_000L, report.log.single().atMs)
        assertTrue("replay took $elapsedMs ms of real time", elapsedMs < 2_000)
    }

    @Test
    fun contextChangesAreMergedIntoFullState() {
        var stub: StubEngine? = null
        ScenarioRunner { clock, log -> StubEngine(clock, log).also { stub = it } }.run(
            ScenarioLoader.parse(
                """{"name":"ctx","description":"d","steps":[
                   {"at_ms":0,"context":{"moving":true}},
                   {"at_ms":10,"context":{"listening":true}},
                   {"at_ms":20,"context":{"moving":false}},
                   {"at_ms":30,"candidate":{"text":"hi","source":"voice","type":"scene_answer","event_id":"c1"}}],
                   "expect":[{"event_id":"c1","decision":"spoken"}]}"""
            )
        )
        // Order: listening, speaking, moving, navigation, offline, sos.
        assertEquals(
            listOf(
                listOf(false, false, true, false, false, false),
                listOf(true, false, true, false, false, false),
                listOf(true, false, false, false, false, false)
            ),
            stub!!.contexts
        )
    }

    @Test
    fun reportsMismatchesInsteadOfThrowing() {
        val scenario = ScenarioLoader.parse(resource("/scenarios/sample.json"))
            .let { it.copy(expect = it.expect.map { e -> e.copy(decision = Decision.QUEUED) }) }
        val report = ScenarioRunner { clock, log -> StubEngine(clock, log) }.run(scenario)
        assertEquals(2, report.mismatches.size)
        assertTrue(report.mismatches[0], report.mismatches[0].contains("expected queued, got dropped"))
    }

    @Test
    fun eventRejectedByIntakeNeverReachesTheEngine() {
        val scenario = ScenarioLoader.parse(resource("/scenarios/sample.json"))
        val bad = scenario.steps[1].event!!.copy(risk = 500)
        val withBad = scenario.copy(
            steps = scenario.steps.toMutableList().also { it[1] = it[1].copy(event = bad) },
            expect = scenario.expect.drop(1)
        )
        var stub: StubEngine? = null
        val report = ScenarioRunner { clock, log -> StubEngine(clock, log).also { stub = it } }.run(withBad)
        assertEquals(listOf("evt_s1: risk must be between 0 and 100"), report.rejectedByIntake)
        assertEquals(listOf(0L, 500L), stub!!.seenAt)
    }

    @Test
    fun criticalInterruptsInfoWithRealInterruptRules() {
        var engine: InterruptRulesEngine? = null
        val report = ScenarioRunner { clock, log -> InterruptRulesEngine(clock, log).also { engine = it } }
            .run(ScenarioLoader.parse(resource("/scenarios/critical_interrupts_info.json")))
        assertTrue(report.mismatches.toString(), report.passed)
        assertEquals(listOf("cand_read", "evt_car"), engine!!.speaker.spoken.map { it.utteranceId })
        assertEquals(listOf<String?>("cand_read"), engine!!.speaker.stopped)
    }

    @Test
    fun trackerFixtureReplays() {
        val scenario = ScenarioLoader.parse(resource("/scenarios/tracker_builder_events.json"))
        // The scenario must carry exactly the EventBuilder fixtures (AVN-45), so it fails if they drift apart.
        val fixtures = File(System.getProperty("avn.repoRoot"), "contracts/fixtures/events")
            .listFiles { f -> f.name.startsWith("builder_") }!!
            .map { Json.decodeFromString(Event.serializer(), it.readText()) }
            .toSet()
        assertEquals(fixtures, scenario.steps.mapNotNull { it.event }.toSet())

        val report = ScenarioRunner { clock, log -> InterruptRulesEngine(clock, log) }.run(scenario)
        assertTrue(report.mismatches.toString(), report.passed)
        assertTrue(report.rejectedByIntake.isEmpty())
        report.logLines().forEach(::println) // Runner log = evidence (Notion: "evidence: runner log").
        assertTrue(report.logLines().none { it.contains("chair") || it.contains("person\"") })
    }
}
