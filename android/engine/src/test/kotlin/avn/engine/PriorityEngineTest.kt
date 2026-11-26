package avn.engine

import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Level
import avn.contracts.Side
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.scenario.ScenarioLoader
import avn.engine.scenario.ScenarioRunner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notion test name: test_engine_v1_end_to_end_fake_speaker (WS16-03). */
class PriorityEngineTest {
    private val clock = FakeClock()
    private val speaker = FakeSpeaker()
    private val engine = PriorityEngine(clock, speaker)

    private var n = 0
    private fun event(
        type: EventType,
        label: String,
        side: Side? = Side.CENTER,
        band: DistanceBand? = DistanceBand.NEAR,
        confidence: Double = 0.9,
        source: Source = Source.CAMERA
    ) = Event("evt_${++n}", source, type, label, side, band, confidence, 0, clock.nowMillis())

    private fun last() = engine.decisions.last()

    @Test
    fun configIsProposedAndFallsBackToDefaults() {
        assertEquals("PROPOSED", EngineConfig.load().status)
        assertEquals(EngineConfig.DEFAULT, EngineConfig.load())
        assertEquals(EngineConfig.DEFAULT, EngineConfig.load("/config/nope.json"))
        assertEquals(0.40, EngineConfig.DEFAULT.minConfidenceFor("Car"), 1e-9)
        assertEquals(0.35, EngineConfig.DEFAULT.minConfidenceFor("chair"), 1e-9)
    }

    @Test
    fun test_engine_v1_end_to_end_fake_speaker() {
        engine.updateContext(moving = true)

        // 1. Approaching car: Critical, spoken at once, phrased by side and band (never meters).
        val car = event(EventType.VEHICLE_APPROACHING, "car", Side.RIGHT, DistanceBand.NEAR)
        engine.submit(car)
        assertEquals(Decision.SPOKEN, last().decision)
        assertEquals(Level.CRITICAL, last().level)
        assertEquals("Car approaching on your right, close", speaker.spoken.last().text)
        assertEquals(false, speaker.spoken.last().interruptible)

        // 2. Chair at medium distance while walking: risk 38 < 45 -> below threshold.
        clock.advanceBy(100)
        engine.submit(event(EventType.OBSTACLE_AHEAD, "chair", Side.CENTER, DistanceBand.MEDIUM))
        assertEquals(Decision.SUPPRESSED, last().decision)
        assertEquals(Reason.BELOW_THRESHOLD, last().reason)

        // 3. Chair close ahead: Warning, waits behind the Critical.
        clock.advanceBy(100)
        val chair = event(EventType.OBSTACLE_AHEAD, "chair")
        engine.submit(chair)
        assertEquals(Decision.QUEUED, last().decision)
        assertEquals(Level.WARNING, last().level)
        assertEquals(54, last().risk)

        // 4. Same chair again while it is queued: duplicate.
        clock.advanceBy(100)
        engine.submit(event(EventType.OBSTACLE_AHEAD, "chair"))
        assertEquals(Reason.DUPLICATE, last().reason)

        // 5. Low-confidence person: gate 1 drops it.
        engine.submit(event(EventType.PERSON_CLOSE, "person", Side.LEFT, confidence = 0.2))
        assertEquals(Decision.DROPPED, last().decision)
        assertEquals(Reason.LOW_CONFIDENCE, last().reason)

        // 6. Car finishes -> the queued chair speaks.
        engine.onUtteranceFinished(car.id)
        assertEquals("Chair ahead, close", speaker.spoken.last().text)
        assertEquals(chair.id, engine.speakingNow?.utteranceId)

        // 7. Same chair after it was spoken, inside 8 s: cooldown.
        engine.onUtteranceFinished(chair.id)
        clock.advanceBy(2_000)
        engine.submit(event(EventType.OBSTACLE_AHEAD, "chair"))
        assertEquals(Reason.COOLDOWN, last().reason)

        // 8. Malformed event: dropped, never thrown.
        engine.submit(
            Event("bad id", Source.CAMERA, EventType.OBSTACLE_AHEAD, "chair", Side.LEFT, DistanceBand.NEAR, 2.0, 0, 0)
        )
        assertEquals(Decision.DROPPED, last().decision)
        assertEquals("invalid_event", last().note)

        assertEquals(7, engine.decisions.size)
        val out = File("build/reports/engine-v1-decisions.jsonl")
        out.parentFile.mkdirs()
        out.writeText(engine.decisions.joinToString("\n") { it.toJsonLine() } + "\n")
    }

    @Test
    fun criticalNeedsPersistenceElseItSpeaksAsWarning() {
        val cfg = EngineConfig.DEFAULT.copy(criticalRisk = 50, warningRisk = 30)
        val e = PriorityEngine(clock, speaker, cfg)
        e.submit(event(EventType.OBSTACLE_AHEAD, "chair")) // risk 54, first sighting
        assertEquals(Level.WARNING, e.decisions.last().level)
        clock.advanceBy(500)
        e.submit(event(EventType.OBSTACLE_AHEAD, "chair")) // second sighting inside 1.5 s
        assertEquals(Level.CRITICAL, e.decisions.last().level)
        // Not persistent if the sightings are too far apart.
        clock.advanceBy(5_000)
        e.submit(event(EventType.OBSTACLE_AHEAD, "bench"))
        clock.advanceBy(2_000)
        e.submit(event(EventType.OBSTACLE_AHEAD, "bench"))
        assertEquals(Level.WARNING, e.decisions.last().level)
    }

    @Test
    fun aiAndNonHazardResultsAreNeverCriticalOrWarning() {
        for (type in listOf(
            EventType.SCENE_ANSWER,
            EventType.TEXT_RESULT,
            EventType.SCREEN_SUMMARY,
            EventType.NOTIFICATION
        )) {
            engine.submit(event(type, "result", null, null, 1.0, Source.VOICE))
            assertEquals(type.name, Level.INFO, engine.decisions.last().level)
        }
        // A camera-sourced scene answer is still not a hazard.
        engine.submit(event(EventType.SCENE_ANSWER, "a car may be near", confidence = 1.0))
        assertEquals(Level.INFO, engine.decisions.last().level)
    }

    @Test
    fun crowdedSceneRaisesTheWarningFloor() {
        engine.updateContext(moving = true)
        listOf("bench", "table", "pole", "sign").forEach {
            engine.submit(event(EventType.OBSTACLE_AHEAD, it, Side.LEFT, DistanceBand.FAR))
            clock.advanceBy(200)
        }
        engine.submit(event(EventType.OBSTACLE_AHEAD, "chair")) // risk 54 < crowded floor 60
        assertEquals(Reason.BELOW_THRESHOLD, engine.decisions.last().reason)
        assertTrue(engine.decisions.last().threshold.contains("crowded"))
        engine.submit(event(EventType.VEHICLE_APPROACHING, "car", Side.LEFT)) // Critical still speaks
        assertEquals(Decision.SPOKEN, engine.decisions.last().decision)
    }

    @Test
    fun stationaryInfoIsHeldUntilIdleAndUserRequestsBypassThresholds() {
        engine.updateContext(moving = true)
        engine.submit(
            SpeechCandidate(
                "It says pharmacy",
                Source.VOICE,
                EventType.TEXT_RESULT,
                userRequested = true,
                eventId = "evt_ask"
            )
        )
        assertEquals(Decision.SPOKEN, engine.decisions.last().decision)
        assertEquals("bypass (asked)", engine.decisions.last().threshold)
        engine.onUtteranceFinished("evt_ask")
        engine.submit(event(EventType.NOTIFICATION, "Ahmed", null, null, 1.0, Source.NOTIFICATION))
        assertEquals(Decision.HELD, engine.decisions.last().decision)
        assertNull("notification label is private and never logged", engine.decisions.last().label)
    }

    @Test
    fun sosCountdownIsCriticalAndHoldsEverythingElse() {
        engine.updateContext(sosActive = true)
        engine.submit(SpeechCandidate("Sending SOS in 5", Source.SYSTEM, EventType.SOS_COUNTDOWN, eventId = "evt_sos"))
        assertEquals(Level.CRITICAL, engine.decisions.last().level)
        assertEquals(Decision.SPOKEN, engine.decisions.last().decision)
        engine.submit(event(EventType.OBSTACLE_AHEAD, "chair"))
        assertEquals(Decision.HELD, engine.decisions.last().decision)
    }

    @Test
    fun decisionLogIsBounded() {
        val e = PriorityEngine(clock, speaker, EngineConfig.DEFAULT.copy(decisionLogSize = 5))
        repeat(12) { e.submit(event(EventType.PERSON_CLOSE, "person", confidence = 0.1)) }
        assertEquals(5, e.decisions.size)
    }

    /**
     * The WS16-09 golden scenarios, replayed through the production engine instead of a test wiring.
     * crowded_room is replayed in its engine-v1 form (crowded_room_engine_v1.json): engine v1 adds the
     * crowded-scene floor from architecture.md section 5, which the original wiring does not have.
     * cooldown_dedup is a CooldownGate-only scenario (fixed risk, no speech ever finishes) and stays
     * with CooldownGateTest.
     */
    @Test
    fun goldenScenariosReplayThroughEngineV1() {
        val report = StringBuilder()
        for (name in listOf("obstacle", "crowded_room_engine_v1", "approaching_vehicle", "notification")) {
            val scenario = ScenarioLoader.parse(javaClass.getResource("/scenarios/$name.json")!!.readText())
            val result = ScenarioRunner { c, log -> PriorityEngine(c, FakeSpeaker(), log = log) }.run(scenario)
            report.append("$name: ${if (result.passed) "PASS" else "FAIL ${result.mismatches}"}\n")
        }
        File("build/reports/engine-v1-golden.txt").apply { parentFile.mkdirs() }.writeText(report.toString())
        assertTrue(report.toString(), !report.contains("FAIL"))
    }
}
