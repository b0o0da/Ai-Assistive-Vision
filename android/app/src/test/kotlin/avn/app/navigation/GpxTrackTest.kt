package avn.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class GpxTrackTest {
    @Test
    fun parsesLatLonAndTimeForEveryTrkpt() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="30.0436" lon="31.2359"><time>2026-01-01T09:00:00Z</time></trkpt>
              <trkpt lat="30.0447" lon="31.2359"><time>2026-01-01T09:00:02Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val fixes = GpxTrack.parse(xml)

        assertEquals(2, fixes.size)
        assertEquals(30.0436, fixes[0].lat, 1e-9)
        assertEquals(31.2359, fixes[0].lon, 1e-9)
        assertEquals(GpxTrack.DEFAULT_ACCURACY_M, fixes[0].accuracyM)
        assertEquals(1_767_258_000_000L, fixes[0].timestampMs)
        assertEquals(1_767_258_002_000L, fixes[1].timestampMs)
    }

    @Test
    fun fallsBackToAFixedIntervalWhenTimeIsMissing() {
        val xml = """
            <gpx><trk><trkseg>
              <trkpt lat="1.0" lon="2.0"/>
              <trkpt lat="1.0" lon="2.0"/>
              <trkpt lat="1.0" lon="2.0"/>
            </trkseg></trk></gpx>
        """.trimIndent()

        val fixes = GpxTrack.parse(xml)

        assertEquals(
            listOf(0L, GpxTrack.DEFAULT_INTERVAL_MS, 2 * GpxTrack.DEFAULT_INTERVAL_MS),
            fixes.map {
                it.timestampMs
            }
        )
    }

    @Test
    fun emptyTrackParsesToAnEmptyList() {
        val xml = "<gpx><trk><trkseg></trkseg></trk></gpx>"

        assertEquals(emptyList<LocationFix>(), GpxTrack.parse(xml))
    }
}
