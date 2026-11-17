package avn.engine

import avn.contracts.DistanceBand
import avn.contracts.Event
import avn.contracts.EventType
import avn.contracts.Side
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Weights for [RiskScorer], loaded from `config/risk.json` (android/CLAUDE.md: "never hard-code
 * thresholds in code"). Every number is a **proposed** starting value from architecture.md section 5
 * and the file says so in its `status` field. Parsed by hand, like [SpeechQueueConfig].
 */
data class RiskConfig(
    val status: String,
    val maxRisk: Int,
    val base: Map<EventType, Int>,
    val staticVehicleBase: Int,
    val staticVehicleLabels: Set<String>,
    val bandWeight: Map<DistanceBand, Double>,
    val sideWeight: Map<Side, Double>,
    val approachBonus: Double
) {
    companion object {
        /** Mirrors `config/risk.json`; used only if the resource is missing or unreadable (fail soft). */
        val DEFAULT = RiskConfig(
            status = "PROPOSED",
            maxRisk = 100,
            base = mapOf(
                EventType.VEHICLE_APPROACHING to 95,
                EventType.OBSTACLE_AHEAD to 60,
                EventType.PERSON_CLOSE to 55,
                EventType.PERSON_KNOWN to 30,
                EventType.PERSON_UNKNOWN to 30,
                EventType.SCENE_ANSWER to 20,
                EventType.TEXT_RESULT to 20,
                EventType.SCREEN_SUMMARY to 20,
                EventType.NOTIFICATION to 20,
                EventType.NAV_INSTRUCTION to 20,
                EventType.SYSTEM_NOTICE to 20,
                EventType.SOS_COUNTDOWN to 100
            ),
            staticVehicleBase = 50,
            staticVehicleLabels = setOf("car", "bus", "truck", "motorcycle", "bicycle", "vehicle"),
            bandWeight = mapOf(DistanceBand.NEAR to 1.0, DistanceBand.MEDIUM to 0.7, DistanceBand.FAR to 0.3),
            sideWeight = mapOf(Side.CENTER to 1.0, Side.LEFT to 0.85, Side.RIGHT to 0.85),
            approachBonus = 1.1
        )

        fun load(resourcePath: String = "/config/risk.json"): RiskConfig {
            val text = RiskConfig::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.readText()
                ?: return DEFAULT
            return runCatching { parse(text) }.getOrDefault(DEFAULT)
        }

        fun parse(text: String): RiskConfig {
            val root = Json.parseToJsonElement(text).jsonObject
            val vehicle = root["staticVehicle"]!!.jsonObject
            return RiskConfig(
                status = root["status"]!!.jsonPrimitive.content,
                maxRisk = root["maxRisk"]!!.jsonPrimitive.int,
                base = enumMap(root["base"]!!.jsonObject, EventType.entries) { it.jsonPrimitive.int },
                staticVehicleBase = vehicle["base"]!!.jsonPrimitive.int,
                staticVehicleLabels = vehicle["labels"]!!.jsonArray.map {
                    it.jsonPrimitive.content.lowercase()
                }.toSet(),
                bandWeight = enumMap(root["bandWeight"]!!.jsonObject, DistanceBand.entries) { it.jsonPrimitive.double },
                sideWeight = enumMap(root["sideWeight"]!!.jsonObject, Side.entries) { it.jsonPrimitive.double },
                approachBonus = root["approachBonus"]!!.jsonPrimitive.double
            )
        }

        /** Config keys are the wire names (`vehicle_approaching`, `near`), i.e. the lowercase enum names. */
        private fun <E : Enum<E>, V> enumMap(
            obj: JsonObject,
            values: List<E>,
            read: (kotlinx.serialization.json.JsonElement) -> V
        ): Map<E, V> = values.mapNotNull { e -> obj[e.name.lowercase()]?.let { e to read(it) } }.toMap()
    }
}

/**
 * Risk score 0..100 (WS16-04, architecture.md section 5):
 * `risk = base(type, label) x band_weight x side_weight x confidence x approach_bonus`, capped.
 *
 * - `obstacle_ahead` with a vehicle label is a static vehicle and uses the static-vehicle base.
 * - `vehicle_approaching` ignores side for severity (the side stays in the message, not the score).
 * - A missing band or side (non-hazard results) weighs 1.0, so answers and text keep their base.
 * - [approaching] is the tracker's "box growth persisted over the minimum window" flag. [Event] has
 *   no field for it (the contract is frozen), so the caller passes it.
 *
 * Producers send `risk = 0`; this is where the engine computes the real value. Pure function, no clock.
 */
class RiskScorer(private val config: RiskConfig = RiskConfig.load()) {
    fun score(event: Event, approaching: Boolean = false): Int {
        val base = baseFor(event)
        val band = event.distanceBand?.let { config.bandWeight[it] } ?: 1.0
        val side = if (event.type == EventType.VEHICLE_APPROACHING) {
            1.0
        } else {
            event.side?.let { config.sideWeight[it] } ?: 1.0
        }
        val confidence = event.confidence.coerceIn(0.0, 1.0)
        val approach = if (approaching) config.approachBonus else 1.0
        val raw = base * band * side * confidence * approach
        return Math.round(raw).toInt().coerceIn(0, config.maxRisk)
    }

    /** Returns [event] with its `risk` field filled in. */
    fun scored(event: Event, approaching: Boolean = false): Event = event.copy(risk = score(event, approaching))

    private fun baseFor(event: Event): Double {
        val label = event.label.trim().lowercase()
        if (event.type == EventType.OBSTACLE_AHEAD && label in config.staticVehicleLabels) {
            return config.staticVehicleBase.toDouble()
        }
        return (config.base[event.type] ?: 0).toDouble()
    }
}
