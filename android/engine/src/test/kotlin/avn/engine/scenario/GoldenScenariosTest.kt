package avn.engine.scenario

import avn.contracts.Event
import avn.contracts.Level
import avn.contracts.SpeechCandidate
import avn.contracts.SpeechRequest
import avn.engine.Clock
import avn.engine.CooldownGate
import avn.engine.Decision
import avn.engine.DedupKey
import avn.engine.EngineInput
import avn.engine.FakeSpeaker
import avn.engine.GateResult
import avn.engine.InterruptRules
import avn.engine.RiskScorer
import avn.engine.SpeechItem
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS16-10: replays [obstacle.json], [crowded_room.json], [approaching_vehicle.json] and
 * [notification.json] (WS16-09) against the real engine pieces -- [RiskScorer], [CooldownGate]
 * and [InterruptRules] wired together -- so a change to any one of cooldown, duplicate,
 * interrupt or queue behaviour fails a scenario here instead of only in a unit test for that
 * piece alone. Pure Kotlin, injected [Clock], no Android dependencies, never touches the camera,
 * mic or network: the scenario JSON is the only input.
 */
class GoldenScenariosTest {
    private fun resource(path: String) = javaClass.getResource(path)!!.readText()

    /**
     * Wires [RiskScorer] -> [CooldownGate] -> [InterruptRules] the way the real app will
     * (architecture.md section 5): an event is scored, gated for cooldown/duplicate, then handed
     * to the interrupt rules; a candidate skips scoring and the gate (it is not camera-sourced)
     * and goes straight to the interrupt rules, same as [SpeechCandidate]'s doc comment describes.
     */
    private class FullEngine(clock: Clock, private val log: DecisionLog) : EngineInput {
        private val speaker = FakeSpeaker()
        private val riskScorer = RiskScorer()
        private val cooldownGate = CooldownGate(clock)
        private val interruptRules = InterruptRules(speaker, clock)

        /** Dedup key of each event that passed the gate, by the id it was submitted under
         * (== [SpeechRequest.utteranceId]), so [activeDedupKeys] can tell which keys are still
         * speaking or queued. CooldownGate itself has no notion of "currently active". */
        private val dedupKeyOf = mutableMapOf<String, String>()

        private fun levelFor(risk: Int) = when {
            risk >= 80 -> Level.CRITICAL
            risk >= 45 -> Level.WARNING
            else -> Level.INFO
        }

        private fun activeDedupKeys(): Set<String> {
            val activeIds = buildSet {
                interruptRules.speakingNow?.utteranceId?.let(::add)
                interruptRules.queuedRequests.forEach { add(it.utteranceId) }
            }
            return activeIds.mapNotNull { dedupKeyOf[it] }.toSet()
        }

        override fun submit(event: Event) {
            val scored = riskScorer.scored(event)
            val level = levelFor(scored.risk)
            when (val result = cooldownGate.check(scored, level, activeDedupKeys())) {
                is GateResult.Suppressed -> log.record(event.id, Decision.SUPPRESSED, result.reason)
                GateResult.Pass -> {
                    dedupKeyOf[event.id] = DedupKey.of(scored)
                    val request =
                        SpeechRequest(event.id, event.label, level, event.source, level != Level.CRITICAL, event.id)
                    val decision = interruptRules.submit(SpeechItem(request, dedupKey = DedupKey.of(scored)))
                    log.record(event.id, decision, null)
                }
            }
        }

        override fun submit(candidate: SpeechCandidate) {
            val id = candidate.eventId ?: "cand"
            val request = SpeechRequest(id, candidate.text, Level.INFO, candidate.source, true, id)
            val decision = interruptRules.submit(SpeechItem(request, userRequested = candidate.userRequested))
            log.record(id, decision, null)
        }

        override fun updateContext(
            listening: Boolean,
            speaking: Boolean,
            moving: Boolean,
            navigationActive: Boolean,
            offline: Boolean,
            sosActive: Boolean
        ) = interruptRules.updateContext(listening, speaking, moving, navigationActive, offline, sosActive)
    }

    private fun replays(fileName: String) {
        val scenario = ScenarioLoader.parse(resource("/scenarios/$fileName"))
        val report = ScenarioRunner { clock, log -> FullEngine(clock, log) }.run(scenario)
        assertTrue(report.mismatches.joinToString("\n"), report.passed)
        assertTrue(report.rejectedByIntake.toString(), report.rejectedByIntake.isEmpty())
        report.logLines().forEach(::println) // Runner log = evidence (Notion: "evidence: runner log").
    }

    @Test
    fun obstacleCooldownThenInterruptThenCooldownExpiry() = replays("obstacle.json")

    @Test
    fun crowdedRoomDuplicateAndQueueOverflow() = replays("crowded_room.json")

    @Test
    fun approachingVehicleInterruptsThenQueuesBehindItself() = replays("approaching_vehicle.json")

    @Test
    fun notificationHeldThenCooldownOnRepeat() = replays("notification.json")

    /** A changed rule must fail a scenario (done-when), not pass silently. */
    @Test
    fun aChangedExpectationFailsTheReplay() {
        val scenario = ScenarioLoader.parse(resource("/scenarios/obstacle.json"))
            .let { it.copy(expect = it.expect.map { e -> e.copy(decision = Decision.DROPPED) }) }
        val report = ScenarioRunner { clock, log -> FullEngine(clock, log) }.run(scenario)
        assertTrue(report.mismatches.isNotEmpty())
    }
}
