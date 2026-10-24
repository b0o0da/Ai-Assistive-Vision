package avn.app.sos

import avn.app.core.logging.JsonLinesLogger
import avn.app.core.state.AppStateStore
import avn.app.data.SettingEntity
import avn.app.data.SettingKeys
import avn.app.data.SettingsDao
import avn.app.navigation.LocationFix
import avn.app.navigation.LocationSource
import avn.contracts.Event
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class RecordingEngineInput : EngineInput {
    val events = mutableListOf<Event>()
    val speeches = mutableListOf<SpeechCandidate>()

    override fun submit(event: Event) {
        events.add(event)
    }

    override fun submit(candidate: SpeechCandidate) {
        speeches.add(candidate)
    }
}

private class FakeSettingsDao(private var contacts: String? = null) : SettingsDao {
    override fun observe(name: String): Flow<String?> = emptyFlow()

    override suspend fun get(name: String): String? = if (name == SettingKeys.EMERGENCY_CONTACTS) contacts else null

    override suspend fun put(setting: SettingEntity) {
        if (setting.name == SettingKeys.EMERGENCY_CONTACTS) contacts = setting.value
    }
}

private class FixedLocationSource(private val fix: LocationFix?) : LocationSource {
    override fun locations(): Flow<LocationFix> = emptyFlow()

    override suspend fun lastKnown(): LocationFix? = fix
}

private class RecordingSmsSender : SmsSender {
    data class Sent(val to: String, val message: String)

    val sent = mutableListOf<Sent>()

    override fun send(to: String, message: String) {
        sent.add(Sent(to, message))
    }
}

/**
 * Covers [RealSos]'s glue around [SosCountdownState] -- trigger/cancel speak and flip
 * [avn.app.core.state.AppState.sosActive] synchronously (no waiting needed); the countdown's
 * completion (send-or-no-contacts) runs on [RealSos]'s own background coroutine after a real
 * 5-second delay, so those two tests poll [AppStateStore] instead of controlling a virtual clock --
 * [RealSos] intentionally owns its own [kotlinx.coroutines.CoroutineScope] rather than taking an
 * injected one, so there is no dispatcher to swap in for `runTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealSosTest {
    private val log = JsonLinesLogger(RuntimeEnvironment.getApplication())

    private fun newSos(
        contacts: String? = "+1555,+1556",
        location: LocationFix? = LocationFix(30.0, 31.0, 5f, 0L),
        smsSender: RecordingSmsSender = RecordingSmsSender(),
        engineInput: RecordingEngineInput = RecordingEngineInput(),
        store: AppStateStore = AppStateStore()
    ): RealSos = RealSos(
        locationSource = FixedLocationSource(location),
        settingsDao = FakeSettingsDao(contacts),
        smsSender = smsSender,
        engineInput = engineInput,
        store = store,
        log = log
    )

    private fun awaitSosActiveFalse(store: AppStateStore, timeoutMs: Long = 7_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (store.state.value.sosActive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertFalse("sosActive still true after ${timeoutMs}ms -- send never completed", store.state.value.sosActive)
    }

    @Test
    fun triggerSpeaksAndMarksSosActiveImmediately() {
        val engineInput = RecordingEngineInput()
        val store = AppStateStore()
        newSos(engineInput = engineInput, store = store).trigger(SosTrigger.BUTTON)

        assertTrue(store.state.value.sosActive)
        assertEquals(1, engineInput.speeches.size)
        assertTrue(engineInput.speeches[0].text.contains("Emergency alert triggered"))
    }

    @Test
    fun secondTriggerWhileCountingDownIsIgnored() {
        val engineInput = RecordingEngineInput()
        val sos = newSos(engineInput = engineInput)

        sos.trigger(SosTrigger.BUTTON)
        sos.trigger(SosTrigger.KEYWORD)

        assertEquals(1, engineInput.speeches.size)
    }

    @Test
    fun cancelMidCountdownSpeaksAndClearsSosActive() {
        val engineInput = RecordingEngineInput()
        val store = AppStateStore()
        val sos = newSos(engineInput = engineInput, store = store)

        sos.trigger(SosTrigger.BUTTON)
        sos.cancel()

        assertFalse(store.state.value.sosActive)
        assertEquals(2, engineInput.speeches.size)
        assertTrue(engineInput.speeches[1].text.contains("cancelled"))
    }

    @Test
    fun cancelWithoutATriggerIsANoOp() {
        val engineInput = RecordingEngineInput()
        newSos(engineInput = engineInput).cancel()

        assertTrue(engineInput.speeches.isEmpty())
    }

    @Test
    fun uncancelledCountdownSendsSmsToEveryContactWithLocation() {
        val sms = RecordingSmsSender()
        val engineInput = RecordingEngineInput()
        val store = AppStateStore()
        val sos = newSos(
            contacts = "+1555, +1556",
            location = LocationFix(30.0, 31.0, 5f, 0L),
            smsSender = sms,
            engineInput = engineInput,
            store = store
        )

        sos.trigger(SosTrigger.BUTTON)
        awaitSosActiveFalse(store)

        assertEquals(2, sms.sent.size)
        assertEquals("+1555", sms.sent[0].to)
        assertEquals("+1556", sms.sent[1].to)
        assertTrue(sms.sent[0].message.contains("maps.google.com"))
        assertTrue(engineInput.speeches.last().text.contains("sent"))
    }

    @Test
    fun uncancelledCountdownWithNoContactsSendsNothingAndSpeaksFailure() {
        val sms = RecordingSmsSender()
        val engineInput = RecordingEngineInput()
        val store = AppStateStore()
        val sos = newSos(contacts = null, smsSender = sms, engineInput = engineInput, store = store)

        sos.trigger(SosTrigger.BUTTON)
        awaitSosActiveFalse(store)

        assertTrue(sms.sent.isEmpty())
        assertTrue(engineInput.speeches.last().text.contains("No emergency contact"))
    }
}
