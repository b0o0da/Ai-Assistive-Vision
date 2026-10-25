package avn.app.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One band's thresholds for one class: the minimum height ratio and minimum bottom-edge ratio
 * (both box-dimension / frame-height) that qualify a box for that band -- see [DistanceBander]. */
data class BandTier(val heightRatio: Float, val bottomRatio: Float)

/** A class's full threshold set: the near tier and the medium tier. Anything clearing neither is far. */
data class ClassBands(val near: BandTier, val medium: BandTier)

/**
 * Per-class [DistanceBander] thresholds, loaded from `config/bands.json` on the classpath
 * (architecture.md WS-06 Definition of Done: "band thresholds calibrated and documented";
 * android/CLAUDE.md: "never hard-code thresholds in code"). Parsed by hand through
 * `kotlinx.serialization.json.JsonElement` rather than `Json.decodeFromString`, the same pattern
 * as AVN-40's fixture loader and AVN-56's `queue.json` -- no need for the `kotlin.serialization`
 * compiler plugin in `:app` for one small config file.
 *
 * AVN-43's ticket names the deliverable `config/bands.yaml`; it is JSON here instead. There is no
 * YAML library in `gradle/libs.versions.toml`, and android/CLAUDE.md says not to add a dependency
 * outside the catalog without asking, so this keeps the format every other config file in the repo
 * already uses (`engine_config.json`, `queue.json`) rather than adding a new dependency for one file.
 */
class BandThresholds private constructor(
    private val default: ClassBands,
    private val perClass: Map<String, ClassBands>
) {
    /** [ClassBands] for [label], falling back to the `default` entry for any class not listed. */
    fun forClass(label: String): ClassBands = perClass[label] ?: default

    companion object {
        /** Fail-soft fallback mirroring `config/bands.json`, used only if the resource is missing
         * -- a missing config file must never crash the engine. */
        val DEFAULT = BandThresholds(
            default = ClassBands(near = BandTier(0.35f, 0.75f), medium = BandTier(0.15f, 0.45f)),
            perClass = emptyMap()
        )

        fun load(resourcePath: String = "/config/bands.json"): BandThresholds {
            val text = BandThresholds::class.java.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.readText()
                ?: return DEFAULT
            val root = Json.parseToJsonElement(text).jsonObject
            val default = parseClassBands(root["default"]!!.jsonObject)
            val perClass = root["classes"]!!.jsonObject.mapValues { (_, element) ->
                parseClassBands(element.jsonObject)
            }
            return BandThresholds(default, perClass)
        }

        private fun parseClassBands(element: JsonObject): ClassBands {
            val near = element["near"]!!.jsonObject
            val medium = element["medium"]!!.jsonObject
            return ClassBands(
                near = BandTier(
                    heightRatio = near["heightRatio"]!!.jsonPrimitive.float,
                    bottomRatio = near["bottomRatio"]!!.jsonPrimitive.float
                ),
                medium = BandTier(
                    heightRatio = medium["heightRatio"]!!.jsonPrimitive.float,
                    bottomRatio = medium["bottomRatio"]!!.jsonPrimitive.float
                )
            )
        }
    }
}
