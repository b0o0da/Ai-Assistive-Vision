package avn.engine.scenario

import avn.engine.Decision
import avn.engine.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notion test name: test_scenario_loads. */
class ScenarioLoaderTest {
    private fun resource(path: String): String {
        val url = javaClass.getResource(path)
        assertNotNull("missing test resource $path", url)
        return url!!.readText()
    }

    private fun rejects(json: String, messagePart: String) {
        val error = assertThrows(IllegalArgumentException::class.java) { ScenarioLoader.parse(json) }
        assertTrue("expected '$messagePart' in: ${error.message}", error.message!!.contains(messagePart))
    }

    @Test
    fun sampleScenarioLoads() {
        val scenario = ScenarioLoader.parse(resource("/scenarios/sample.json"))

        assertEquals("sample", scenario.name)
        assertEquals(3, scenario.steps.size)
        assertEquals(true, scenario.steps[0].context?.moving)
        assertEquals("evt_s1", scenario.steps[1].event?.id)
        assertEquals("cand_s1", scenario.steps[2].candidate?.eventId)
        assertEquals(ExpectedDecision("evt_s1", Decision.DROPPED, Reason.LOW_CONFIDENCE), scenario.expect[0])
        assertEquals(ExpectedDecision("cand_s1", Decision.SPOKEN, null), scenario.expect[1])
    }

    @Test
    fun rejectsUnknownKey() = rejects(
        """{"name":"x","description":"d","steps":[{"at_ms":0,"context":{"moving":true}}],
           "expect":[],"typo":1}""",
        "not valid"
    )

    @Test
    fun rejectsStepWithTwoInputs() = rejects(
        """{"name":"x","description":"d",
           "steps":[{"at_ms":0,"context":{"moving":true},
                     "candidate":{"text":"hi","source":"voice","type":"scene_answer","event_id":"c1"}}],
           "expect":[{"event_id":"c1","decision":"spoken"}]}""",
        "exactly one"
    )

    @Test
    fun rejectsTimeGoingBackwards() = rejects(
        """{"name":"x","description":"d",
           "steps":[{"at_ms":500,"context":{"moving":true}},
                    {"at_ms":100,"candidate":{"text":"hi","source":"voice","type":"scene_answer","event_id":"c1"}}],
           "expect":[{"event_id":"c1","decision":"spoken"}]}""",
        "earlier than"
    )

    @Test
    fun rejectsEmptyContextChange() = rejects(
        """{"name":"x","description":"d",
           "steps":[{"at_ms":0,"context":{}},
                    {"at_ms":1,"candidate":{"text":"hi","source":"voice","type":"scene_answer","event_id":"c1"}}],
           "expect":[{"event_id":"c1","decision":"spoken"}]}""",
        "sets no field"
    )

    @Test
    fun rejectsExpectationForUnknownId() = rejects(
        """{"name":"x","description":"d",
           "steps":[{"at_ms":0,"candidate":{"text":"hi","source":"voice","type":"scene_answer","event_id":"c1"}}],
           "expect":[{"event_id":"nope","decision":"spoken"}]}""",
        "unknown id"
    )

    @Test
    fun rejectsUnknownDecisionValue() = rejects(
        """{"name":"x","description":"d",
           "steps":[{"at_ms":0,"candidate":{"text":"hi","source":"voice","type":"scene_answer","event_id":"c1"}}],
           "expect":[{"event_id":"c1","decision":"shouted"}]}""",
        "not valid"
    )
}
