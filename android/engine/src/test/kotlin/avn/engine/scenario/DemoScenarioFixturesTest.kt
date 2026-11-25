package avn.engine.scenario

import org.junit.Assert.assertEquals
import org.junit.Test

/** WS16-09: the demo-step-2/3/4 scenario fixtures parse and validate against SCHEMA.md. */
class DemoScenarioFixturesTest {
    private fun resource(path: String) = javaClass.getResource(path)!!.readText()

    private fun loads(fileName: String, expectedName: String) {
        val scenario = ScenarioLoader.parse(resource("/scenarios/$fileName"))
        assertEquals(expectedName, scenario.name)
        assertEquals(scenario.name, fileName.removeSuffix(".json"))
    }

    @Test
    fun obstacleScenarioLoads() = loads("obstacle.json", "obstacle")

    @Test
    fun crowdedRoomScenarioLoads() = loads("crowded_room.json", "crowded_room")

    @Test
    fun approachingVehicleScenarioLoads() = loads("approaching_vehicle.json", "approaching_vehicle")

    @Test
    fun notificationScenarioLoads() = loads("notification.json", "notification")
}
