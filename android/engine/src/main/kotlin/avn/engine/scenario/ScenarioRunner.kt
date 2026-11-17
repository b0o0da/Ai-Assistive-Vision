package avn.engine.scenario

import avn.engine.Clock
import avn.engine.Decision
import avn.engine.EngineInput
import avn.engine.Reason
import avn.engine.intake.EventIntake
import avn.engine.intake.IntakeResult
import avn.engine.wire

/**
 * Virtual time for replays (WS16-02). Time only moves when the runner says so, so a 10-minute
 * scenario replays in milliseconds and gives the same result on every run.
 */
class VirtualClock(startMillis: Long = 0L) : Clock {
    private var millis = startMillis

    override fun nowMillis(): Long = millis

    fun advanceTo(millis: Long) {
        require(millis >= this.millis) { "virtual time cannot go back (${this.millis} -> $millis)" }
        this.millis = millis
    }
}

/** Where an engine under test reports what it decided. The id is an Event.id or SpeechCandidate.event_id. */
fun interface DecisionLog {
    fun record(id: String, decision: Decision, reason: Reason?)
}

/** One decision as the runner saw it, stamped with virtual time. */
data class LoggedDecision(val atMs: Long, val id: String, val decision: Decision, val reason: Reason?)

/** Result of one replay. [log] is the evidence; [mismatches] is empty when the scenario passed. */
data class ReplayReport(
    val scenario: String,
    val log: List<LoggedDecision>,
    val rejectedByIntake: List<String>,
    val mismatches: List<String>
) {
    val passed: Boolean get() = mismatches.isEmpty()

    /**
     * The runner log as JSON lines: time, id, decision, reason. No speech text, labels or other
     * content, so it is safe to attach as evidence (android/CLAUDE.md logging rule).
     */
    fun logLines(): List<String> = log.map { d ->
        val reason = d.reason?.let { ",\"reason\":\"${it.wire}\"" } ?: ""
        "{\"scenario\":\"$scenario\",\"at_ms\":${d.atMs},\"id\":\"${d.id}\",\"decision\":\"${d.decision.wire}\"$reason}"
    }
}

/**
 * Replays a [Scenario] against an engine (WS16-02).
 *
 * For each step the runner moves the [VirtualClock] to `at_ms`, then:
 * - `event`: passes it through [EventIntake]; accepted events go to [EngineInput.submit], rejected
 *   ones are listed in [ReplayReport.rejectedByIntake] and never reach the engine;
 * - `candidate`: [EngineInput.submit];
 * - `context`: merges the change into the current context and calls [EngineInput.updateContext]
 *   with the full state, exactly like voice and navigation will.
 *
 * Then it compares the recorded decisions with `expect`. A reason is only compared when the
 * scenario gives one. The engine is built per run by [engineFactory] from the runner's clock and
 * log, so every run starts clean.
 */
class ScenarioRunner(private val engineFactory: (Clock, DecisionLog) -> EngineInput) {
    fun run(scenario: Scenario): ReplayReport {
        ScenarioLoader.validate(scenario)
        val clock = VirtualClock()
        val log = mutableListOf<LoggedDecision>()
        val engine = engineFactory(clock) { id, decision, reason ->
            log += LoggedDecision(clock.nowMillis(), id, decision, reason)
        }
        val rejected = mutableListOf<String>()
        var context = ContextState()

        for (step in scenario.steps) {
            clock.advanceTo(step.atMs)
            step.event?.let { event ->
                when (val result = EventIntake.check(event)) {
                    is IntakeResult.Accepted -> engine.submit(result.event)
                    is IntakeResult.Rejected -> rejected += "${event.id}: ${result.reason}"
                }
            }
            step.candidate?.let { engine.submit(it) }
            step.context?.let { change ->
                context = context.merge(change)
                engine.updateContext(
                    listening = context.listening,
                    speaking = context.speaking,
                    moving = context.moving,
                    navigationActive = context.navigationActive,
                    offline = context.offline,
                    sosActive = context.sosActive
                )
            }
        }
        return ReplayReport(scenario.name, log.toList(), rejected.toList(), compare(scenario, log))
    }

    private fun compare(scenario: Scenario, log: List<LoggedDecision>): List<String> =
        scenario.expect.mapNotNull { expected ->
            val actual = log.lastOrNull { it.id == expected.eventId }
            when {
                actual == null -> "${expected.eventId}: expected ${expected.decision.wire}, engine logged nothing"
                actual.decision != expected.decision ->
                    "${expected.eventId}: expected ${expected.decision.wire}, got ${actual.decision.wire}"
                expected.reason != null && actual.reason != expected.reason ->
                    "${expected.eventId}: expected reason ${expected.reason.wire}, got ${actual.reason?.wire ?: "none"}"
                else -> null
            }
        }

    private data class ContextState(
        val listening: Boolean = false,
        val speaking: Boolean = false,
        val moving: Boolean = false,
        val navigationActive: Boolean = false,
        val offline: Boolean = false,
        val sosActive: Boolean = false
    ) {
        fun merge(change: ContextChange) = ContextState(
            listening = change.listening ?: listening,
            speaking = change.speaking ?: speaking,
            moving = change.moving ?: moving,
            navigationActive = change.navigationActive ?: navigationActive,
            offline = change.offline ?: offline,
            sosActive = change.sosActive ?: sosActive
        )
    }
}
