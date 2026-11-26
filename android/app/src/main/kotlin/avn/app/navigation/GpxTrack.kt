package avn.app.navigation

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Minimal GPX 1.1 `<trkpt>` reader for the WS-20 desk/CI route replay (architecture.md: "GPX
 * replay lets it be developed at a desk"; `tests/fixtures/routes/pharmacy_walk.gpx` is the stored
 * fixture). Reads only lat/lon/time -- GPX carries no accuracy field, so every fix gets
 * [DEFAULT_ACCURACY_M]. A point missing `<time>` falls back to one point every
 * [DEFAULT_INTERVAL_MS], counted from the first point.
 */
object GpxTrack {
    const val DEFAULT_ACCURACY_M = 5f
    const val DEFAULT_INTERVAL_MS = 2_000L

    fun parse(xml: String): List<LocationFix> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val points = document.getElementsByTagName("trkpt")
        return (0 until points.length).map { index ->
            val point = points.item(index) as Element
            val lat = point.getAttribute("lat").toDouble()
            val lon = point.getAttribute("lon").toDouble()
            val timestampMs = point.getElementsByTagName("time").item(0)
                ?.textContent
                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: (index * DEFAULT_INTERVAL_MS)
            LocationFix(lat, lon, DEFAULT_ACCURACY_M, timestampMs)
        }
    }
}
