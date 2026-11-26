package avn.app.core

import android.content.Context
import avn.app.core.logging.JsonLinesLogger
import avn.app.voice.FakeSpeaker
import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Level
import avn.contracts.Side
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.Clock
import avn.engine.Decision
import avn.engine.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The app's engine binding: events reach FakeSpeaker through engine v1, and every decision is visible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppPriorityEngineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val log = JsonLinesLogger(context)
    private val speaker = FakeSpeaker(log)
    private val clock = object : Clock {
        var now = 1_000L
        override fun nowMillis() = now
    }
    private val engine = AppPriorityEngine(speaker, log, clock)

    private fun event(
        id: String,
        label: String,
        type: EventType = EventType.OBSTACLE_AHEAD,
        side: Side = Side.CENTER,
        band: DistanceBand = DistanceBand.NEAR,
        confidence: Double = 0.9
    ) = Event(
        id = id,
        timestamp = clock.now,
        source = Source.CAMERA,
        type = type,
        label = label,
        side = side,
        distanceBand = band,
        confidence = confidence,
        risk = 0
    )

    @Test
    fun approachingCarIsSpokenAsCriticalThroughTheSpeaker() {
        engine.submit(event("evt_car", "car", type = EventType.VEHICLE_APPROACHING, side = Side.RIGHT))

        val spoken = speaker.spoken.value.single()
        assertEquals(Level.CRITICAL, spoken.level)
        assertTrue(spoken.text, spoken.text.startsWith("Car approaching on your right"))
        assertEquals(Decision.SPOKEN, engine.decisions.value.single().decision)
    }

    @Test
    fun lowConfidenceEventIsDroppedWithAReasonAndNotSpoken() {
        engine.submit(event("evt_low", "chair", confidence = 0.2))

        assertTrue(speaker.spoken.value.isEmpty())
        val record = engine.decisions.value.single()
        assertEquals(Decision.DROPPED, record.decision)
        assertEquals(Reason.LOW_CONFIDENCE, record.reason)
    }

    @Test
    fun anInstantSpeakerNeverLeavesTheEngineStuckSpeaking() {
        engine.submit(event("evt_a", "car", type = EventType.VEHICLE_APPROACHING, side = Side.LEFT))
        clock.now += 100
        engine.submit(event("evt_b", "bus", type = EventType.VEHICLE_APPROACHING, side = Side.RIGHT))

        assertEquals(listOf("evt_a", "evt_b"), speaker.spoken.value.map { it.utteranceId })
        assertEquals(listOf(Decision.SPOKEN, Decision.SPOKEN), engine.decisions.value.map { it.decision })
    }

    @Test
    fun userRequestedAnswerBypassesThresholdsAndIsLogged() {
        engine.submit(SpeechCandidate("I see: chair.", Source.SYSTEM, EventType.SYSTEM_NOTICE, userRequested = true))

        assertEquals("I see: chair.", speaker.spoken.value.single().text)
        assertEquals("bypass (asked)", engine.decisions.value.single().threshold)
    }
}
