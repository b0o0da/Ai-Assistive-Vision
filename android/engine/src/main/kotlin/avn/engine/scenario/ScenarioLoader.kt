package avn.engine.scenario

import kotlinx.serialization.json.Json

/**
 * Reads and checks scenario JSON. Unknown keys are rejected, so a typo in a scenario fails loudly
 * instead of being silently ignored. Throws IllegalArgumentException with the step number on any problem.
 */
object ScenarioLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): Scenario {
        val scenario = try {
            json.decodeFromString(Scenario.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("scenario JSON is not valid: ${e.message}", e)
        }
        validate(scenario)
        return scenario
    }

    fun validate(scenario: Scenario) {
        val name = scenario.name
        require(name.isNotBlank()) { "scenario name must not be blank" }
        require(scenario.steps.isNotEmpty()) { "scenario '$name' has no steps" }
        require(scenario.expect.isNotEmpty()) { "scenario '$name' has no expected decisions" }

        val ids = mutableSetOf<String>()
        var previousAt = 0L
        scenario.steps.forEachIndexed { index, step ->
            val where = "scenario '$name' step ${index + 1}"
            val filled = listOfNotNull(step.event, step.candidate, step.context).size
            require(filled == 1) { "$where must set exactly one of event, candidate, context (found $filled)" }
            require(step.atMs >= 0) { "$where has negative at_ms" }
            require(step.atMs >= previousAt) { "$where at_ms ${step.atMs} is earlier than the step before it" }
            previousAt = step.atMs

            step.context?.let { require(!it.isEmpty) { "$where context change sets no field" } }
            step.event?.let { event ->
                require(event.confidence in 0.0..1.0) { "$where confidence must be between 0 and 1" }
                require(ids.add(event.id)) { "$where repeats id '${event.id}'" }
            }
            step.candidate?.eventId?.let { id -> require(ids.add(id)) { "$where repeats id '$id'" } }
        }

        scenario.expect.forEachIndexed { index, expected ->
            require(expected.eventId in ids) {
                "scenario '$name' expect ${index + 1} refers to unknown id '${expected.eventId}'"
            }
        }
    }
}
