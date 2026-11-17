package avn.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads the real, shipped `detector.json` / `detector_pothole.json` (not fixtures) to catch
 * exactly the failure [DetectorConfig.DEFAULT]'s doc comment warns about: a config that no
 * longer agrees with the `.onnx` asset actually built into the app.
 */
class DetectorConfigTest {
    @Test
    fun theRealGeneralDetectorJsonLoadsAndMergesVehicleTypesIntoTransportation() {
        val config = DetectorConfig.load()
        val expectedClasses = setOf(
            "person",
            "transportation",
            "bench",
            "chair",
            "fire hydrant",
            "stop sign",
            "parking meter"
        )
        assertEquals(expectedClasses, config.classes)
        assertEquals(CocoLabels.names, config.labels)
        assertEquals("yolo11m_int8_coco_stock.onnx", config.modelAsset)
        assertEquals(
            setOf("bicycle", "car", "motorcycle", "bus", "truck"),
            config.classMap.filterValues { it == "transportation" }.keys
        )
        // "pothole" is intentionally NOT in this config -- it has no COCO equivalent and is
        // handled by the separate detector_pothole.json / POTHOLE_DEFAULT.
        assertTrue("pothole" !in config.classes)
    }

    @Test
    fun theRealGeneralDetectorJsonAgreesWithDefaultSoAFallbackLoadNeverMisdecodes() {
        val loaded = DetectorConfig.load()
        assertEquals(DetectorConfig.DEFAULT.labels, loaded.labels)
        assertEquals(DetectorConfig.DEFAULT.classes, loaded.classes)
        assertEquals(DetectorConfig.DEFAULT.classMap, loaded.classMap)
        assertEquals(DetectorConfig.DEFAULT.modelAsset, loaded.modelAsset)
    }

    @Test
    fun theRealPotholeDetectorJsonLoadsAsItsOwnSingleClassModel() {
        val loaded = DetectorConfig.load("/config/detector_pothole.json", DetectorConfig.POTHOLE_DEFAULT)
        assertEquals(setOf("pothole"), loaded.classes)
        assertEquals(listOf("pothole"), loaded.labels)
        assertEquals("pothole_keremberke_n.onnx", loaded.modelAsset)
        assertEquals(DetectorConfig.POTHOLE_DEFAULT, loaded)
    }

    @Test
    fun defaultIsInternallyValid() {
        val default = DetectorConfig.DEFAULT
        assertTrue(default.labels.isNotEmpty() && default.labels.toSet().size == default.labels.size)
        val reachable = default.labels.toSet() + default.classMap.values
        assertTrue(default.classes.isNotEmpty() && default.classes.all { it in reachable })
    }

    @Test
    fun potholeDefaultIsInternallyValid() {
        val default = DetectorConfig.POTHOLE_DEFAULT
        assertTrue(default.classes.isNotEmpty() && default.classes.all { it in default.labels })
    }
}
