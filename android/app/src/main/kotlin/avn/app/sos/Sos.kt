package avn.app.sos

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SmsManager
import avn.app.core.logging.JsonLinesLogger
import avn.app.core.state.AppEvent
import avn.app.core.state.AppStateStore
import avn.app.data.SettingKeys
import avn.app.data.SettingsDao
import avn.app.navigation.LocationSource
import avn.contracts.EventType
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SosTrigger { KEYWORD, INTENT, BUTTON }

/** SMS goes only to contacts from settings. The emergency-number path does not exist in any build. */
interface Sos {
    fun trigger(source: SosTrigger)

    fun cancel()
}

@Singleton
class FakeSos @Inject constructor(private val log: JsonLinesLogger) : Sos {
    override fun trigger(source: SosTrigger) = log.log("sos", "fake_trigger", fields = mapOf("source" to source))

    override fun cancel() = log.log("sos", "fake_cancel")
}

/**
 * Pure state machine for the trigger/cancel/send countdown -- split out from [RealSos]'s
 * Android-specific side effects (SMS, location, speech, coroutine delay) so the safety-critical
 * decision ("can't double-trigger", "cancel only mid-countdown", "can't send twice") is
 * unit-testable without a device. Same reasoning as why CentroidTracker/DistanceEstimator are
 * separated from a detector.
 */
class SosCountdownState {
    enum class State { IDLE, COUNTING_DOWN, SENT, CANCELLED }

    var state: State = State.IDLE
        private set

    /** Returns true if this call actually started a countdown (false if one was already running). */
    fun trigger(): Boolean {
        if (state == State.COUNTING_DOWN) return false
        state = State.COUNTING_DOWN
        return true
    }

    /** Returns true if this call actually cancelled an in-progress countdown. */
    fun cancel(): Boolean {
        if (state != State.COUNTING_DOWN) return false
        state = State.CANCELLED
        return true
    }

    /** Returns true if the countdown was still active and is now marked sent (false if cancelled first). */
    fun markSent(): Boolean {
        if (state != State.COUNTING_DOWN) return false
        state = State.SENT
        return true
    }

    fun reset() {
        state = State.IDLE
    }
}

/**
 * Seam around the actual SMS send call, same Fake/Real split as [LocationSource]/[avn.app.navigation.RouteClient]
 * elsewhere in this codebase. [SmsManager] leans on static factory methods and real device telephony
 * state, which can't be meaningfully exercised in a plain JVM unit test -- this interface is what lets
 * [RealSos]'s trigger/cancel/send *decision* flow be tested without any of that.
 */
interface SmsSender {
    fun send(to: String, message: String)
}

/** Real [SmsSender]: thin wrapper around [SmsManager]. */
@Singleton
class AndroidSmsSender @Inject constructor(
    @ApplicationContext private val context: Context
) : SmsSender {
    @SuppressLint("MissingPermission") // caller (PermissionManager-gated UI) only invokes after grant
    override fun send(to: String, message: String) {
        getSmsManager().sendTextMessage(to, null, message, null, null)
    }

    // minSdk is 31 (Build.VERSION_CODES.S), so the SDK_INT check this used to have is always true --
    // lint (ObsoleteSdkInt) flagged the pre-S SmsManager.getDefault() branch as dead code.
    private fun getSmsManager(): SmsManager = context.getSystemService(SmsManager::class.java)
}

/**
 * Real [Sos]: trigger (voice keyword, intent or button) -> spoken cancel countdown -> SMS with a
 * maps link to the current location, sent to every contact in [SettingKeys.EMERGENCY_CONTACTS]
 * (comma-separated phone numbers written by the settings screen; android/CLAUDE.md -- "SMS goes
 * only to contacts from config/settings", "the emergency-number path is disabled in all builds").
 * The countdown decision lives in [SosCountdownState]; this class is the glue around it, and
 * speaks only through [EngineInput.submit] (one-speaker rule) instead of calling TTS directly.
 */
@Singleton
class RealSos @Inject constructor(
    private val locationSource: LocationSource,
    private val settingsDao: SettingsDao,
    private val smsSender: SmsSender,
    private val engineInput: EngineInput,
    private val store: AppStateStore,
    private val log: JsonLinesLogger
) : Sos {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val countdown = SosCountdownState()
    private val countdownSeconds = 5

    @Volatile
    private var countdownJob: Job? = null

    override fun trigger(source: SosTrigger) {
        if (!countdown.trigger()) {
            log.log("sos", "trigger_ignored_already_counting", fields = mapOf("source" to source))
            return
        }
        store.dispatch(AppEvent.SosActive(true))
        log.log("sos", "triggered", fields = mapOf("source" to source))
        speak("Emergency alert triggered. Sending your location in $countdownSeconds seconds. Tap again to cancel.")
        countdownJob = scope.launch {
            delay(countdownSeconds * 1000L)
            if (countdown.markSent()) {
                sendSos()
            }
        }
    }

    override fun cancel() {
        if (countdown.cancel()) {
            countdownJob?.cancel()
            store.dispatch(AppEvent.SosActive(false))
            log.log("sos", "cancelled")
            speak("Emergency alert cancelled.")
        }
    }

    private suspend fun sendSos() {
        val contacts = settingsDao.get(SettingKeys.EMERGENCY_CONTACTS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (contacts.isEmpty()) {
            log.log("sos", "send_no_contacts")
            speak("No emergency contact is set up. I could not send your location.")
            store.dispatch(AppEvent.SosActive(false))
            countdown.reset()
            return
        }

        val location = locationSource.lastKnown()
        val message = if (location != null) {
            "Emergency! I need help. My location: https://maps.google.com/?q=${location.lat},${location.lon}"
        } else {
            "Emergency! I need help. Location unavailable."
        }

        try {
            contacts.forEach { smsSender.send(it, message) }
            log.log("sos", "sent", fields = mapOf("contacts" to contacts.size, "has_location" to (location != null)))
            speak("Emergency message sent.")
        } catch (e: Exception) {
            log.log("sos", "send_failed", fields = mapOf("error" to e.javaClass.simpleName))
            speak("Failed to send the emergency message. Please try again.")
        } finally {
            store.dispatch(AppEvent.SosActive(false))
            countdown.reset()
        }
    }

    private fun speak(text: String) {
        engineInput.submit(
            SpeechCandidate(
                text = text,
                source = Source.SYSTEM,
                type = EventType.SOS_COUNTDOWN,
                userRequested = true,
                timeSensitive = true
            )
        )
    }
}
