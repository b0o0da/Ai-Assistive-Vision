package avn.app.navigation

import android.content.Context
import avn.app.core.logging.JsonLinesLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** WS20-01: the bundled GPX resource (`android/app/src/main/resources/routes/pharmacy_walk.gpx`) loads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FakeLocationSourceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val source = FakeLocationSource(JsonLinesLogger(context))

    @Test
    fun lastKnownReturnsTheFirstStoredFix() = runBlocking {
        val fix = source.lastKnown()

        assertNotNull(fix)
        assertEquals(30.0436, fix!!.lat, 1e-9)
        assertEquals(31.2359, fix.lon, 1e-9)
    }
}
