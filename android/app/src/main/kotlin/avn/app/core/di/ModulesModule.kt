package avn.app.core.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import avn.app.BuildConfig
import avn.app.camera.FakeFrameSource
import avn.app.camera.FrameSource
import avn.app.core.FakeEngineInput
import avn.app.core.consent.ConsentManager
import avn.app.data.AlertLogDao
import avn.app.data.AvnDatabase
import avn.app.data.KnownPeopleDao
import avn.app.data.SettingsDao
import avn.app.data.api.ApiClient
import avn.app.data.api.BackendClient
import avn.app.data.api.BackendConfig
import avn.app.data.api.ConsentGate
import avn.app.navigation.FakeLocationSource
import avn.app.navigation.FakeNavigation
import avn.app.navigation.LocationSource
import avn.app.navigation.Navigation
import avn.app.screen.FakeNotificationSource
import avn.app.screen.FakeScreenActions
import avn.app.screen.FakeScreenAi
import avn.app.screen.FakeScreenCapture
import avn.app.screen.FakeScreenReader
import avn.app.screen.NotificationSource
import avn.app.screen.ScreenActions
import avn.app.screen.ScreenAi
import avn.app.screen.ScreenCapture
import avn.app.screen.ScreenReader
import avn.app.sos.FakeSos
import avn.app.sos.Sos
import avn.app.vision.Detector
import avn.app.vision.FakeDetector
import avn.app.vision.FakeOcrEngine
import avn.app.vision.FakeTracker
import avn.app.vision.OcrEngine
import avn.app.vision.Tracker
import avn.app.voice.ActionRouter
import avn.app.voice.EmergencyKeywordDetector
import avn.app.voice.FakeActionRouter
import avn.app.voice.FakeEmergencyKeywordDetector
import avn.app.voice.FakeIntentRouter
import avn.app.voice.FakeSpeaker
import avn.app.voice.FakeSttEngine
import avn.app.voice.FakeWakeWord
import avn.app.voice.IntentRouter
import avn.app.voice.SttEngine
import avn.app.voice.WakeWord
import avn.engine.EngineInput
import avn.engine.SpeechOutput
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one place a seam is bound. Every seam is a fake in Phase 1.
 *
 * When a real implementation lands, replace that seam's @Binds with a @Provides that picks by
 * `"<seam>" in BuildConfig.REAL_SEAMS` (gradle property `avn.realSeams`), so integration swaps one
 * seam per build. Callers never branch on fake vs real.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ModulesModule {
    @Binds
    abstract fun engineInput(fake: FakeEngineInput): EngineInput

    @Binds
    abstract fun speechOutput(fake: FakeSpeaker): SpeechOutput

    @Binds
    abstract fun frameSource(fake: FakeFrameSource): FrameSource

    @Binds
    abstract fun detector(fake: FakeDetector): Detector

    @Binds
    abstract fun tracker(fake: FakeTracker): Tracker

    @Binds
    abstract fun ocrEngine(fake: FakeOcrEngine): OcrEngine

    @Binds
    abstract fun wakeWord(fake: FakeWakeWord): WakeWord

    @Binds
    abstract fun emergencyKeywordDetector(fake: FakeEmergencyKeywordDetector): EmergencyKeywordDetector

    @Binds
    abstract fun sttEngine(fake: FakeSttEngine): SttEngine

    @Binds
    abstract fun intentRouter(fake: FakeIntentRouter): IntentRouter

    @Binds
    abstract fun actionRouter(fake: FakeActionRouter): ActionRouter

    @Binds
    abstract fun screenReader(fake: FakeScreenReader): ScreenReader

    @Binds
    abstract fun screenActions(fake: FakeScreenActions): ScreenActions

    @Binds
    abstract fun screenCapture(fake: FakeScreenCapture): ScreenCapture

    @Binds
    abstract fun screenAi(fake: FakeScreenAi): ScreenAi

    @Binds
    abstract fun notificationSource(fake: FakeNotificationSource): NotificationSource

    @Binds
    abstract fun locationSource(fake: FakeLocationSource): LocationSource

    @Binds
    abstract fun navigation(fake: FakeNavigation): Navigation

    @Binds
    abstract fun sos(fake: FakeSos): Sos
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AvnDatabase =
        Room.databaseBuilder(context, AvnDatabase::class.java, "avn.db").build()

    @Provides
    fun settingsDao(db: AvnDatabase): SettingsDao = db.settings()

    @Provides
    fun alertLogDao(db: AvnDatabase): AlertLogDao = db.alertLog()

    @Provides
    fun knownPeopleDao(db: AvnDatabase): KnownPeopleDao = db.knownPeople()

    @Provides
    @Singleton
    fun consentManager(@ApplicationContext context: Context): ConsentManager =
        ConsentManager(context.getSharedPreferences("consent", Context.MODE_PRIVATE))

    @Provides
    @Singleton
    fun apiClient(consent: ConsentManager): ApiClient = BackendClient(
        config = BackendConfig(BuildConfig.BACKEND_URL, BuildConfig.API_KEY),
        consent = ConsentGate(consent::isGranted),
        log = { line -> Log.d("avn.api", line) }
    )
}
