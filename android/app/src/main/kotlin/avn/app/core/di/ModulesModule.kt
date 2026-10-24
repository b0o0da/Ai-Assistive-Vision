package avn.app.core.di

import android.content.Context
import androidx.room.Room
import avn.app.BuildConfig
import avn.app.camera.FakeFrameSource
import avn.app.camera.FrameSource
import avn.app.core.FakeEngineInput
import avn.app.core.consent.ConsentManager
import avn.app.core.logging.JsonLinesLogger
import avn.app.data.AlertLogDao
import avn.app.data.AvnDatabase
import avn.app.data.KnownPeopleDao
import avn.app.data.SettingsDao
import avn.app.navigation.FakeLocationSource
import avn.app.navigation.FakeNavigation
import avn.app.navigation.GpsLocationSource
import avn.app.navigation.HttpRouteClient
import avn.app.navigation.LocationSource
import avn.app.navigation.Navigation
import avn.app.navigation.RealNavigation
import avn.app.navigation.RouteClient
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
import avn.app.sos.AndroidSmsSender
import avn.app.sos.FakeSos
import avn.app.sos.RealSos
import avn.app.sos.SmsSender
import avn.app.sos.Sos
import avn.app.vision.Detector
import avn.app.vision.FakeDetector
import avn.app.vision.FakeOcrEngine
import avn.app.vision.FakeTracker
import avn.app.vision.MlKitOcrEngine
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

/** `avn.realSeams` as a set, e.g. `"ocr,navigation"` -> `{"ocr", "navigation"}`. */
private fun realSeamKeys(): Set<String> =
    BuildConfig.REAL_SEAMS.split(",").map { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() }

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

    // ocrEngine: see RealSeamsModule below.

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

    // ocrEngine, locationSource, navigation and sos are real seams now -- see RealSeamsModule
    // below, which @Provides them picked by BuildConfig.REAL_SEAMS instead of a fixed @Binds.

    /**
     * Not a ModuleRegistry seam itself (same reasoning as [RouteClient] below) -- a dependency of
     * the real [Sos], always bound to the real implementation so it's exercised whenever "sos" is
     * in [BuildConfig.REAL_SEAMS]. Tests construct a fake [SmsSender] directly, no Hilt needed.
     */
    @Binds
    abstract fun smsSender(real: AndroidSmsSender): SmsSender
}

/**
 * The four seams with a real implementation so far. Each is picked by
 * `"<seam>" in BuildConfig.REAL_SEAMS` (gradle property `avn.realSeams`) instead of a fixed
 * `@Binds`, per ModulesModule's own doc comment. Both the fake and the real candidate are
 * constructed either way -- Dagger resolves every parameter before the body runs -- but every
 * real implementation here is cheap to construct (no I/O in any constructor), so that's fine.
 */
@Module
@InstallIn(SingletonComponent::class)
object RealSeamsModule {
    @Provides
    @Singleton
    fun ocrEngine(fake: FakeOcrEngine, real: MlKitOcrEngine): OcrEngine =
        if ("ocr" in realSeamKeys()) real else fake

    @Provides
    @Singleton
    fun locationSource(fake: FakeLocationSource, real: GpsLocationSource): LocationSource =
        if ("location" in realSeamKeys()) real else fake

    @Provides
    @Singleton
    fun navigation(fake: FakeNavigation, real: RealNavigation): Navigation =
        if ("navigation" in realSeamKeys()) real else fake

    @Provides
    @Singleton
    fun sos(fake: FakeSos, real: RealSos): Sos =
        if ("sos" in realSeamKeys()) real else fake

    /**
     * Not a ModuleRegistry seam by itself -- a dependency *of* the real [Navigation]
     * implementation, the same way [AvnDatabase] is a dependency of [DataModule]'s DAOs. Always
     * the real [HttpRouteClient]; there is no Fake/Real split for it because the frozen `/route`
     * contract has only ever had one backend to call (mock mode is a backend concern, not this
     * client's). Unit tests construct a fake [RouteClient] directly instead.
     */
    @Provides
    @Singleton
    fun routeClient(log: JsonLinesLogger): RouteClient =
        HttpRouteClient(baseUrl = BuildConfig.BACKEND_URL, apiKey = BuildConfig.API_KEY, log = log)
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
}
