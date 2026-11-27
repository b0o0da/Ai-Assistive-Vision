package avn.app.screen.accessibility

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WS17-02 on a device: turns on our accessibility service, switches between 5 real apps with
 * `am start -W`, and reads the tracker's debug log (tag AVN_WINDOW: time + package only).
 * For each switch: launch time reported by the system, and the extra delay until our tracker had
 * the new package (target: under 500 ms). The notification shade must not count as a switch.
 * Run: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=avn.app.screen.accessibility.ActiveWindowOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class ActiveWindowOnDeviceTest {
    // By default a connected UiAutomation suppresses every accessibility service, ours included.
    private val ui = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val service = "avn.app/avn.app.screen.accessibility.AssistantAccessibilityService"

    private fun sh(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    @Before
    fun enableService() {
        sh("settings put secure enabled_accessibility_services $service")
        sh("settings put secure accessibility_enabled 1")
        // Wait until the system has actually bound the service, not a fixed time.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !sh("dumpsys accessibility").contains("label=AVN Assistant")) {
            Thread.sleep(500)
        }
        sh("logcat -c")
    }

    @After
    fun disableService() {
        sh("settings delete secure enabled_accessibility_services")
        sh("settings put secure accessibility_enabled 0")
        sh("input keyevent KEYCODE_HOME")
    }

    private fun launchers(): List<String> =
        sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
            .lines().map { it.trim() }.filter { it.contains('/') }
            .map { it.substringBefore('/') }.distinct()
            .filter { it != "avn.app" && it != "avn.app.test" }

    @Test
    fun fiveAppSwitchesAreTrackedWithin500Ms() {
        // Stock apps that open without a first-run dialog or hardware (no camera on a headless emulator).
        val preferred = listOf(
            "com.android.settings",
            "com.google.android.deskclock",
            "com.google.android.contacts",
            "com.google.android.calendar",
            "com.google.android.dialer",
            "com.google.android.documentsui"
        )
        val available = launchers()
        val apps = (
            preferred.filter {
                it in available
            } + available.filter { it !in preferred && "camera" !in it }
            ).take(4)
        assertTrue("need 4 launchable apps, found $apps", apps.size == 4)
        val report = StringBuilder("app,launch_ms,tracker_extra_ms\n")
        val starts = mutableListOf<Pair<String, Long>>()
        for (pkg in apps) {
            val t0 = System.currentTimeMillis()
            val out = sh("am start -W -n ${launchActivity(pkg)}")
            val launch = Regex("TotalTime: (\\d+)").find(out)?.groupValues?.get(1)?.toLong() ?: -1
            starts += pkg to (t0 + maxOf(0, launch))
            Thread.sleep(1_500)
        }
        // The shade is system UI: it must not become "the app in front".
        sh("cmd statusbar expand-notifications")
        Thread.sleep(1_000)
        sh("cmd statusbar collapse")
        // Fifth switch: back to the first app.
        val t0 = System.currentTimeMillis()
        val out = sh("am start -W -n ${launchActivity(apps[0])}")
        val launch = Regex("TotalTime: (\\d+)").find(out)?.groupValues?.get(1)?.toLong() ?: -1
        starts += apps[0] to (t0 + maxOf(0, launch))
        Thread.sleep(1_500)

        val logged = sh("logcat -d -s ${AssistantAccessibilityService.LOG_TAG}:I").lines()
            .mapNotNull { Regex("AVN_WINDOW: (\\d+) (\\S+)").find(it) }
            .map { it.groupValues[2] to it.groupValues[1].toLong() }
        assertFalse("system UI must be ignored: $logged", logged.any { it.first == "com.android.systemui" })

        var cursor = 0
        for ((pkg, launchedAt) in starts) {
            val hit = logged.drop(cursor).indexOfFirst { it.first == pkg }
            assertTrue("no tracker update for $pkg in $logged", hit >= 0)
            val (_, at) = logged[cursor + hit]
            cursor += hit + 1
            val extra = at - launchedAt
            report.append("$pkg,${launchedAt - starts.first().second},$extra\n")
            Log.i("AVN_WINDOW_RESULT", "$pkg tracker_extra_ms=$extra")
            assertTrue("$pkg: tracker updated $extra ms after the app finished launching", extra < 500)
        }
        Log.i("AVN_WINDOW_RESULT", report.toString().replace('\n', ';'))
    }

    private fun launchActivity(pkg: String): String =
        sh("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg").lines().last {
            it.contains('/')
        }.trim()
}
