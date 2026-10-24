package eg.assistive.core.engine

import eg.assistive.core.model.DistanceBand
import eg.assistive.core.model.Event
import eg.assistive.core.model.Priority
import eg.assistive.core.model.Side
import eg.assistive.core.model.SpeechCandidate
import eg.assistive.core.testutil.FakeClock
import eg.assistive.core.testutil.RecordingOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PriorityEngineTest {
    private val clock = FakeClock(1_000)
    private val out = RecordingOutput()
    private val engine = PriorityEngine(out, clock)

    private fun ev(
        id: String = "e", type: String = "obstacle_ahead", label: String = "Chair ahead",
        conf: Double? = 0.9, risk: Priority? = null, band: DistanceBand = DistanceBand.NEAR,
        side: Side = Side.CENTER, track: Int? = null,
    ) = Event(id, "cv", type, label, side, band, conf, risk, 0L, track)

    private fun vehicle(id: String = "v", band: DistanceBand = DistanceBand.NEAR, track: Int? = 7, conf: Double? = 0.95) =
        ev(id, "vehicle_approaching", "Vehicle approaching", conf, null, band, Side.LEFT, track)

    // ---- levels
    @Test fun vehicleIsCriticalAndSpokenImmediately() {
        val r = engine.submit(vehicle())
        assertEquals(Priority.CRITICAL, r.priority)
        assertEquals(Outcome.SPOKEN_NOW, r.outcome)
        assertEquals("Vehicle approaching", out.last.text)
        assertEquals(false, out.last.request.interruptible) // a critical alert is not cut by another one
    }

    @Test fun vehicleWithMissingFieldsStaysCritical() {
        assertEquals(Priority.CRITICAL, engine.submit(Event("x", "vehicle_detection", "vehicle_approaching")).priority)
    }

    @Test fun farOrUnsureVehicleIsOnlyWarning() {
        assertEquals(Priority.WARNING, engine.submit(vehicle("a", DistanceBand.FAR, 1)).priority)
        assertEquals(Priority.WARNING, engine.submit(vehicle("b", DistanceBand.NEAR, 2, 0.5)).priority)
    }

    @Test fun obstacleAndPlanNameAreWarnings() {
        assertEquals(Priority.WARNING, engine.submit(ev("a", "obstacle_ahead")).priority)
        assertEquals(Priority.WARNING, engine.submit(ev("b", "obstacle", "Pole", side = Side.LEFT)).priority)
    }

    @Test fun producerRiskHintCanRaiseButNeverLower() {
        assertEquals(Priority.CRITICAL, engine.submit(ev("a", risk = Priority.CRITICAL)).priority)
        assertEquals(Priority.CRITICAL, engine.submit(vehicle("b", track = 9).copy(risk = Priority.INFO)).priority)
    }

    @Test fun unknownTypeDefaultsToInfo() {
        engine.updateContext(EngineContext(moving = false))
        assertEquals(Priority.INFO, engine.submit(ev(type = "mystery", label = "Something")).priority)
    }

    // ---- scheduling
    @Test fun criticalInterruptsWarningUsingStopThenSpeak() {
        engine.submit(ev())
        val r = engine.submit(vehicle())
        assertEquals(Outcome.SPOKEN_INTERRUPT, r.outcome)
        assertTrue(out.last.interrupt)
        assertEquals(1, out.stops)
        assertEquals(Outcome.INTERRUPTED, engine.alertLog.entries().first { it.reason == Reason.PREEMPTED }.outcome)
    }

    @Test fun warningWaitsForGapThenSpeaksWhenUtteranceEnds() {
        engine.submit(ev("a", label = "Chair ahead", side = Side.LEFT))
        val r = engine.submit(ev("b", label = "Pole ahead", side = Side.RIGHT))
        assertEquals(Outcome.QUEUED, r.outcome)
        assertEquals(1, out.calls.size)
        out.finishLast()
        assertEquals(listOf("Chair ahead", "Pole ahead"), out.texts)
        assertEquals(Outcome.SPOKEN_FROM_QUEUE, engine.alertLog.entries().last().outcome)
    }

    @Test fun errorCallbackAlsoReleasesTheQueue() {
        engine.submit(ev("a", label = "A", side = Side.LEFT))
        engine.submit(ev("b", label = "B", side = Side.RIGHT))
        out.registered!!.onError(out.last.utteranceId)
        assertEquals(listOf("A", "B"), out.texts)
    }

    @Test fun criticalDoesNotCutAnotherCriticalButGoesFirstInLine() {
        engine.submit(vehicle("v1", track = 1))
        engine.submit(ev("w", label = "Pole ahead", side = Side.RIGHT))
        val r = engine.submit(ev("sos1", "sos", "SOS", conf = null, band = DistanceBand.UNKNOWN, side = Side.UNKNOWN))
        assertEquals(Outcome.QUEUED, r.outcome)
        out.finishLast()
        assertEquals("SOS", out.last.text) // critical goes before the waiting warning
    }

    @Test fun queueIsOrderedByLevelThenRiskScore() {
        engine.submit(ev("first", label = "First", side = Side.LEFT))
        engine.submit(ev("far", label = "Far one", band = DistanceBand.FAR, side = Side.RIGHT))
        engine.submit(ev("near", label = "Near one", band = DistanceBand.NEAR, side = Side.CENTER))
        out.finishLast()
        assertEquals("Near one", out.last.text)
    }

    @Test fun staleUtteranceCallbackIsIgnored() {
        engine.submit(ev("a", label = "A", side = Side.LEFT))
        engine.submit(ev("b", label = "B", side = Side.RIGHT))
        out.registered!!.onDone("not-the-current-utterance")
        out.registered!!.onStopped(out.last.utteranceId) // a stop callback must not release the next item either
        assertEquals(1, out.calls.size)
    }

    @Test fun lostCallbackDoesNotSilenceTheEngineForever() {
        engine.submit(ev("a", label = "A", side = Side.LEFT))
        engine.submit(ev("b", label = "B", side = Side.RIGHT))
        clock.advance(25_000)
        engine.tick()
        assertEquals(listOf("A"), out.texts) // B expired in the queue (ttl 4 s) rather than being spoken late
        assertEquals(Outcome.EXPIRED, engine.alertLog.entries().last().outcome)
        engine.submit(ev("c", label = "C", side = Side.RIGHT, track = 3))
        assertEquals("C", out.last.text) // engine is not stuck
    }

    @Test fun staleWarningExpiresInQueue() {
        engine.submit(ev("a", label = "A", side = Side.LEFT))
        engine.submit(ev("b", label = "B", side = Side.RIGHT))
        clock.advance(5_000)
        engine.tick()
        out.finishLast()
        assertEquals(listOf("A"), out.texts)
        assertTrue(engine.alertLog.entries().any { it.reason == Reason.TTL_EXPIRED })
    }

    @Test fun queueDropsLowestWhenFull() {
        engine.submit(ev("cur", label = "Current", side = Side.LEFT))
        for (i in 1..9) engine.submit(ev("q$i", "obstacle_ahead", "Item $i", band = DistanceBand.FAR, side = Side.RIGHT, track = i))
        assertEquals(8, engine.queuedCount())
        assertTrue(engine.alertLog.entries().any { it.reason == Reason.QUEUE_FULL })
    }

    // ---- suppression
    @Test fun lowConfidenceNoiseIsSuppressedAndLogged() {
        val r = engine.submit(ev(conf = 0.2))
        assertEquals(Outcome.SUPPRESSED, r.outcome)
        assertEquals(Reason.LOW_CONFIDENCE, r.reason)
        assertEquals(0, out.calls.size)
        assertEquals(1, engine.alertLog.suppressed().size)
    }

    @Test fun sosIsNeverTreatedAsNoise() {
        assertTrue(engine.submit(ev(type = "sos", label = "SOS", conf = 0.05)).spoken)
    }

    @Test fun invalidInputIsLoggedNotSpoken() {
        assertEquals(Reason.INVALID, engine.submit(ev(conf = 1.5)).reason)
        assertEquals(Reason.INVALID, engine.submit(Event("z", "cv", "")).reason)
        assertEquals(Reason.INVALID, engine.submit(Event("", "cv", "obstacle_ahead")).reason)
        assertEquals(Reason.INVALID, engine.submit(SpeechCandidate("s", "x", "  ")).reason)
        assertEquals(0, out.calls.size)
    }

    @Test fun cooldownSuppressesRepeatOfSameThingThenAllowsAfterwards() {
        val first = engine.submit(ev("a", track = 3))
        out.finishLast()
        clock.advance(1_000)
        assertEquals(Reason.COOLDOWN, engine.submit(ev("b", track = 3, label = "Chair ahead!")).reason)
        clock.advance(5_000)
        assertTrue(first.spoken && engine.submit(ev("c", track = 3, label = "Chair still ahead")).spoken)
    }

    @Test fun withoutTrackIdSameLabelAndSideIsTheSameThing() {
        engine.submit(ev("a", label = "Chair ahead"))
        out.finishLast()
        clock.advance(500)
        assertEquals(Reason.COOLDOWN, engine.submit(ev("b", label = "Chair ahead")).reason)
    }

    @Test fun gettingCloserBypassesCooldown() {
        engine.submit(vehicle("a", DistanceBand.FAR, track = 7)) // warning, far
        out.finishLast()
        clock.advance(500)
        assertTrue(engine.submit(vehicle("b", DistanceBand.NEAR, track = 7)).spoken) // now critical and closer
    }

    @Test fun sameTextWithinWindowIsDuplicateEvenOnAnotherTrack() {
        engine.submit(ev("a", track = 1, label = "Chair ahead"))
        out.finishLast()
        clock.advance(500)
        assertEquals(Reason.DUPLICATE, engine.submit(ev("b", track = 2, label = "chair ahead ")).reason)
    }

    @Test fun duplicateWaitingInQueueIsSuppressed() {
        engine.submit(ev("a", label = "A", side = Side.LEFT))
        engine.submit(ev("b", label = "Pole ahead", side = Side.RIGHT))
        assertEquals(Reason.DUPLICATE, engine.submit(ev("c", label = "Pole ahead", side = Side.CENTER)).reason)
    }

    // ---- context (updateContext)
    @Test fun infoIsSilentWhileMovingAndSpokenWhenStopped() {
        assertEquals(Reason.INFO_NOT_REQUESTED, engine.submit(ev(type = "scene_description", label = "A shop is ahead")).reason)
        engine.updateContext(EngineContext(moving = false))
        assertTrue(engine.submit(ev("s", type = "scene_description", label = "A shop is ahead")).spoken)
    }

    @Test fun infoIsSilentDuringNavigationAndSos() {
        engine.updateContext(EngineContext(moving = false, navigationActive = true))
        assertEquals(Reason.INFO_NOT_REQUESTED, engine.submit(ev("a", type = "scene_description", label = "Shop")).reason)
        engine.updateContext(EngineContext(moving = false, sosActive = true))
        assertEquals(Reason.INFO_NOT_REQUESTED, engine.submit(ev("b", type = "scene_description", label = "Cafe")).reason)
    }

    @Test fun warningIsHeldWhileListeningButCriticalStillSpeaks() {
        engine.updateContext(EngineContext(listening = true))
        assertEquals(Outcome.QUEUED, engine.submit(ev("a", label = "Chair ahead")).outcome)
        assertEquals(0, out.calls.size)
        assertTrue(engine.submit(vehicle()).spoken)
        out.finishLast()
        assertEquals(listOf("Vehicle approaching"), out.texts) // chair still held
        engine.updateContext(EngineContext(listening = false))
        assertEquals(listOf("Vehicle approaching", "Chair ahead"), out.texts)
    }

    @Test fun userRequestedInfoIsSpokenEvenWhileMovingAndBypassesCooldown() {
        val a = engine.submit(SpeechCandidate("r1", "ocr", "Take two tablets", userRequested = true, dedupeKey = "k"))
        out.finishLast()
        val b = engine.submit(SpeechCandidate("r2", "ocr", "Take two tablets", userRequested = true, dedupeKey = "k"))
        assertTrue(a.spoken && b.spoken)
    }

    @Test fun speechRequestCarriesTheContractFields() {
        engine.submit(SpeechCandidate("n1", "navigation", "Turn right", level = Priority.WARNING, eventId = "evt-9"))
        val req = out.last.request
        assertEquals("Turn right", req.text)
        assertEquals(Priority.WARNING, req.level)
        assertEquals("navigation", req.source)
        assertEquals(true, req.interruptible)
        assertEquals("evt-9", req.eventId)
    }

    // ---- logging and privacy
    @Test fun sensitiveTextNeverAppearsInTheLog() {
        engine.submit(SpeechCandidate("r", "ocr", "Dr Ahmed 0100 123 4567 Panadol", userRequested = true, sensitive = true))
        val dump = engine.alertLog.render() + engine.alertLog.entries().joinToString { it.toString() }
        assertFalse(dump.contains("Panadol"))
        assertTrue(dump.contains("[redacted 30 chars]"))
    }

    @Test fun everyDecisionIsLoggedWithAReason() {
        engine.submit(ev(conf = 0.1))
        engine.submit(ev("b"))
        engine.submit(ev("c", track = 5, label = "Pole", side = Side.LEFT))
        assertEquals(3, engine.alertLog.outcomeCounts().values.sum())
        assertTrue(engine.alertLog.notSpoken().isNotEmpty())
    }

    @Test fun logIsABoundedRingBuffer() {
        val small = PriorityEngine(RecordingOutput(), clock, EngineConfig(logCapacity = 5))
        repeat(20) { small.submit(ev(conf = 0.1)) }
        assertEquals(5, small.alertLog.entries().size)
    }

    // ---- resume after interruption
    @Test fun interruptedUserRequestedReadingResumesAfterCritical() {
        engine.submit(SpeechCandidate("a", "ocr", "Chunk one", userRequested = true, dedupeKey = "1"))
        engine.submit(SpeechCandidate("b", "ocr", "Chunk two", userRequested = true, dedupeKey = "2"))
        assertEquals(Outcome.SPOKEN_INTERRUPT, engine.submit(vehicle()).outcome)
        out.finishLast()
        assertEquals("Chunk one", out.last.text) // the interrupted chunk is repeated first
        out.finishLast()
        assertEquals("Chunk two", out.last.text)
    }
}
