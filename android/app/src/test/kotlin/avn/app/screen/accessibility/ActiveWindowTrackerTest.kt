package avn.app.screen.accessibility

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WS17-02 (Notion test name: ActiveWindowTrackerTest). Evidence: a log of 5 app switches. */
class ActiveWindowTrackerTest {
    private var now = 0L
    private val tracker = ActiveWindowTracker("avn.app", ActiveWindowTracker.SYSTEM_UI_PACKAGES) { now }
    private val stateChanged = WindowEventRouter.TYPE_WINDOW_STATE_CHANGED

    private fun event(atMs: Long, pkg: String?, cls: String?, type: Int = stateChanged): Boolean {
        now = atMs
        return WindowEventRouter.route(type, pkg, cls, tracker)
    }

    @Test
    fun fiveAppSwitchesAreTrackedAndOwnAppAndSystemUiAreIgnored() = runBlocking {
        val log = StringBuilder("at_ms,event_package,event_class,foreground_after\n")
        val events = listOf(
            Triple(0L, "com.whatsapp", "com.whatsapp.HomeActivity"),
            Triple(1_200L, "com.android.systemui", "android.widget.FrameLayout"), // shade pulled down
            Triple(2_000L, "com.facebook.katana", "com.facebook.katana.LoginActivity"),
            Triple(2_300L, "avn.app", "avn.app.ui.MainActivity"), // our own app
            Triple(3_100L, "com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow"),
            Triple(4_000L, "com.android.settings", "com.android.settings.Settings"),
            Triple(
                5_500L,
                "com.google.android.apps.nexuslauncher",
                "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
            ),
            Triple(6_000L, "com.whatsapp", "com.whatsapp.Conversation")
        )
        for ((at, pkg, cls) in events) {
            event(at, pkg, cls)
            log.append("$at,$pkg,$cls,${tracker.foreground.value?.packageName}\n")
        }
        File("build/reports/active-window-switches.csv").apply { parentFile.mkdirs() }.writeText(log.toString())

        assertEquals(
            listOf(
                "com.whatsapp",
                "com.facebook.katana",
                "com.android.settings",
                "com.google.android.apps.nexuslauncher",
                "com.whatsapp"
            ),
            tracker.switches.map { it.packageName }
        )
        // Each switch is stamped with the event's own time: no delay added on our side.
        assertEquals(listOf(0L, 2_000L, 4_000L, 5_500L, 6_000L), tracker.switches.map { it.sinceMs })
        assertEquals("com.whatsapp.Conversation", tracker.foreground.first()?.activity)
    }

    @Test
    fun dialogsInsideTheSameAppKeepTheActivityAndRepeatsAreNotSwitches() {
        assertTrue(event(0, "com.whatsapp", "com.whatsapp.HomeActivity"))
        assertFalse(event(100, "com.whatsapp", "android.widget.FrameLayout"))
        assertEquals("com.whatsapp.HomeActivity", tracker.foreground.value?.activity)
        assertFalse(event(200, "com.whatsapp", "com.whatsapp.Conversation"))
        assertEquals("com.whatsapp.Conversation", tracker.foreground.value?.activity)
        assertEquals(1, tracker.switches.size)
    }

    @Test
    fun otherEventTypesAndBlankPackagesAreIgnored() {
        assertFalse(event(0, "com.whatsapp", "x", type = 0x00000800)) // TYPE_WINDOW_CONTENT_CHANGED
        assertFalse(event(0, null, null))
        assertFalse(event(0, "  ", "x"))
        assertNull(tracker.foreground.value)
    }

    @Test
    fun historyIsBoundedAndTheRouterConstantMatchesAndroid() {
        repeat(30) { event(it * 10L, "pkg.app$it", null) }
        assertEquals(ActiveWindowTracker.HISTORY, tracker.switches.size)
        assertEquals(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            WindowEventRouter.TYPE_WINDOW_STATE_CHANGED
        )
    }

    /** Switches reach the tracker within the service's notificationTimeout, well under the 500 ms target. */
    @Test
    fun serviceConfigDeliversWindowChangesWithin500Ms() {
        val xml = File(
            System.getProperty("avn.repoRoot"),
            "android/app/src/main/res/xml/accessibility_service_config.xml"
        ).readText()
        assertTrue(xml.contains("typeWindowStateChanged"))
        val timeout = Regex("notificationTimeout=\"(\\d+)\"").find(xml)!!.groupValues[1].toInt()
        assertTrue("notificationTimeout $timeout ms", timeout <= 500)
    }
}
