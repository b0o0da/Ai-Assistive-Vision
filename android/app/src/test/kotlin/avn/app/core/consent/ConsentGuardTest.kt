package avn.app.core.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notion tests for WS28-01: test_gate_blocks_camera, test_gate_blocks_cloud, plus revocation. Plain JVM. */
class ConsentGuardTest {
    /** Counts how often a "sensor" or "cloud call" actually ran. */
    private class Counter {
        var calls = 0

        fun run(): String {
            calls++
            return "ran"
        }
    }

    private fun denied(d: ConsentCheck.Denied) = d.spokenMessage

    @Test
    fun gateBlocksCameraWithoutConsent() {
        val gate = ConsentGuard(FakeConsentStore())
        val camera = Counter()

        val result = gate.guard(ConsentScope.CAMERA, ::denied) { camera.run() }

        assertEquals("camera must not start", 0, camera.calls)
        assertEquals(ConsentGuard.deniedMessage(ConsentScope.CAMERA), result)
    }

    @Test
    fun gateBlocksMicrophoneAndScreenWithoutConsent() {
        val gate = ConsentGuard(FakeConsentStore(setOf(ConsentScope.CAMERA)))
        val sensor = Counter()

        gate.guard(ConsentScope.MICROPHONE, ::denied) { sensor.run() }
        gate.guard(ConsentScope.SCREEN_READING, ::denied) { sensor.run() }

        assertEquals("camera consent must not unlock other sensors", 0, sensor.calls)
    }

    @Test
    fun gateBlocksCloudCallWithoutConsent() {
        // Camera consent alone must not allow sending pictures to the cloud.
        val gate = ConsentGuard(FakeConsentStore(setOf(ConsentScope.CAMERA)))
        val cloud = Counter()

        val result = gate.check(ConsentScope.CLOUD_FRAMES)
        gate.guard(ConsentScope.CLOUD_FRAMES, ::denied) { cloud.run() }
        gate.guard(ConsentScope.CLOUD_SCREEN, ::denied) { cloud.run() }

        assertEquals("no request may be sent", 0, cloud.calls)
        assertEquals(ConsentScope.CLOUD_FRAMES, (result as ConsentCheck.Denied).scope)
    }

    @Test
    fun gateAllowsWithConsent() {
        val gate = ConsentGuard(FakeConsentStore(setOf(ConsentScope.CAMERA, ConsentScope.CLOUD_FRAMES)))
        val calls = Counter()

        assertEquals(ConsentCheck.Allowed, gate.check(ConsentScope.CAMERA))
        assertEquals("ran", gate.guard(ConsentScope.CAMERA, ::denied) { calls.run() })
        assertEquals("ran", gate.guard(ConsentScope.CLOUD_FRAMES, ::denied) { calls.run() })
        assertEquals(2, calls.calls)
    }

    @Test
    fun revocationIsHonouredOnTheNextCall() {
        val store = FakeConsentStore(setOf(ConsentScope.MICROPHONE))
        val gate = ConsentGuard(store)
        val mic = Counter()

        gate.guard(ConsentScope.MICROPHONE, ::denied) { mic.run() }
        store.revoke(ConsentScope.MICROPHONE)
        gate.guard(ConsentScope.MICROPHONE, ::denied) { mic.run() }

        assertEquals("only the call before the revoke may run", 1, mic.calls)
    }

    @Test
    fun everyScopeHasAShortSpokenMessage() {
        ConsentScope.entries.forEach { scope ->
            val message = ConsentGuard.deniedMessage(scope)
            assertTrue("$scope message is blank", message.isNotBlank())
            assertTrue("$scope message is too long to speak", message.length <= 80)
        }
    }
}
