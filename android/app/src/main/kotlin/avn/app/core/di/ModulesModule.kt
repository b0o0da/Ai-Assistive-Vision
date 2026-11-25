package avn.app.core.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import avn.app.BuildConfig
import avn.app.camera.CameraXFrameSource
import avn.app.camera.FakeFrameSource
import avn.app.camera.FrameSource
import avn.app.core.FakeEngineInput
import avn.app.core.consent.ConsentGuard
import avn.app.core.consent.ConsentManager
import avn.app.core.logging.JsonLinesLogger
import avn.app.data.AlertLogDao
import avn.app.data.AvnDatabase
import avn.app.data.KnownPeopleDao
import avn.app.data.SettingsDao
import avn.app.data.api.ApiClient
import avn.app.data.api.BackendClient
import avn.app.data.api.BackendConfig
import avn.app.data.api.ConsentGate
import avn.app.navigation.BackendNavigation
import avn.app.navigation.FakeHapticCues
import avn.app.navigation.FakeLocationSource
import avn.app.navigation.FakeNavigation
import avn.app.navigation.FusedLocationSource
import avn.app.navigation.HapticCues
import avn.app.navigation.LocationSource
import avn.app.navigation.Navigation
import avn.app.navigation.VibratorHapticCues
import avn.app.perf.PerfLogger
import avn.app.screen.ApiScreenSummaryClient
import avn.app.screen.FakeNotificationSource
import avn.app.screen.FakeScreenActions
import avn.app.screen.FakeScreenAi
import avn.app.screen.FakeScreenCapture
import avn.app.screen.FakeScreenReader
import avn.app.screen.FakeScreenSummaryClient
import avn.app.screen.NotificationSource
import avn.app.screen.ScreenActions
import avn.app.screen.ScreenAi
import avn.app.screen.ScreenCapture
import avn.app.screen.ScreenReader
import avn.app.screen.ScreenSummaryClient
import avn.app.sos.FakeSos
import avn.app.sos.Sos
import avn.app.vision.AccelerometerMotionEstimator
import avn.app.vision.CompositeDetector
import avn.app.vision.Detector
import avn.app.vision.DetectorConfig
import avn.app.vision.FakeDetector
import avn.app.vision.FakeMotionEstimator
import avn.app.vision.FakeOcrEngine
import avn.app.vision.FakeTracker
import avn.app.vision.IouTracker
import avn.app.vision.MlKitOcrEngine
import avn.app.vision.MlKitTextRecognizer
import avn.app.vision.MotionEstimator
import avn.app.vision.OcrEngine
import avn.app.vision.Tracker
import avn.app.vision.YoloV8OnnxDetector
import avn.app.voice.ActionRouter
import avn.app.voice.EmergencyKeywordDetector
import avn.app.voice.FakeActionRouter
import avn.app.voice.FakeEmergencyKeywordDetector
import avn.app.voice.FakeIntentRouter
import avn.app.voice.FakeSpeaker
import avn.app.voice.FakeSttEngine
import avn.app.voice.FakeWakeWord
import avn.app.voice.IntentRouter
import avn.app.voice.NavigationActionRouter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one place each seam is bound. Camera, detector, and tracker use real implementations in
 * the normal debug build; set `-Pavn.realSeams=` to force their fakes for isolated tests.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ModulesModule {
    @Binds
    abstract fun engineInput(fake: FakeEngineInput): EngineInput

    @Binds
    abstract fun speechOutput(fake: FakeSpeaker): SpeechOutput

    @Binds
    abstract fun wakeWord(fake: FakeWakeWord): WakeWord

    @Binds
    abstract fun emergencyKeywordDetector(fake: FakeEmergencyKeywordDetector): EmergencyKeywordDetector

    @Binds
    abstract fun sttEngine(fake: FakeSttEngine): SttEngine

    @Binds
    abstract fun intentRouter(fake: FakeIntentRouter): IntentRouter

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
    abstract fun sos(fake: FakeSos): Sos

    companion object {
        @Provides
        @Singleton
        fun frameSource(fake: FakeFrameSource, real: CameraXFrameSource): FrameSource =
            if ("camera" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) real else fake

        @Provides
        @Singleton
        fun detector(
            @ApplicationContext context: Context,
            log: JsonLinesLogger,
            perf: PerfLogger,
            fake: FakeDetector
        ): Detector = if ("detector" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) {
            // Two separate checkpoints, two disjoint class sets -- see CompositeDetector and
            // DetectorConfig.DEFAULT/POTHOLE_DEFAULT's doc comments for why.
            CompositeDetector(
                listOf(
                    YoloV8OnnxDetector(context, log, DetectorConfig.load("/config/detector.json"), perf),
                    YoloV8OnnxDetector(
                        context,
                        log,
                        DetectorConfig.load("/config/detector_pothole.json", DetectorConfig.POTHOLE_DEFAULT),
                        perf
                    )
                )
            )
        } else {
            fake
        }

        @Provides
        @Singleton
        fun tracker(perf: PerfLogger, motion: MotionEstimator, fake: FakeTracker): Tracker =
            if ("tracker" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) {
                IouTracker(isUserMoving = motion::isWalking, perf = perf)
            } else {
                fake
            }

        @Provides
        @Singleton
        fun locationSource(fake: FakeLocationSource, real: FusedLocationSource): LocationSource =
            if ("location" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) real else fake

        @Provides
        @Singleton
        fun navigation(fake: FakeNavigation, real: BackendNavigation): Navigation =
            if ("navigation" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) real else fake

        @Provides
        @Singleton
        fun hapticCues(fake: FakeHapticCues, real: VibratorHapticCues): HapticCues =
            if ("haptics" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) real else fake

        /**
         * `NavigationActionRouter` only handles `PlanStep.NAVIGATE`; every other step is a no-op
         * log line until WS-14 builds its own real router (see its doc comment). Keep this seam
         * off by default so enabling it doesn't look like voice control as a whole went real.
         */
        @Provides
        @Singleton
        fun actionRouter(fake: FakeActionRouter, real: NavigationActionRouter): ActionRouter =
            if ("action_router" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) real else fake

        @Provides
        @Singleton
        fun motionEstimator(fake: FakeMotionEstimator, real: AccelerometerMotionEstimator): MotionEstimator =
            if ("motion" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) real else fake

        /** WS07-03. Real engine = ML Kit bundled Latin model, on-device and offline. */
        @Provides
        @Singleton
        fun ocrEngine(fake: FakeOcrEngine, log: JsonLinesLogger, perf: PerfLogger): OcrEngine =
            if ("ocr" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) {
                MlKitOcrEngine(MlKitTextRecognizer(), log, perf)
            } else {
                fake
            }

        /** WS18-02. Real client talks to `/screen/summarize` (mock backend first); fake echoes the tree text. */
        @Provides
        @Singleton
        fun screenSummaryClient(fake: FakeScreenSummaryClient, real: ApiScreenSummaryClient): ScreenSummaryClient =
            if ("screen_summary" in BuildConfig.REAL_SEAMS.split(',').map(String::trim)) real else fake
    }
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

    /** Background work that must outlive a single caller, e.g. navigation's live location tracking. */
    @Provides
    @Singleton
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun apiClient(consent: ConsentManager): ApiClient = BackendClient(
        config = BackendConfig(BuildConfig.BACKEND_URL, BuildConfig.API_KEY),
        consent = ConsentGate(consent::isGranted),
        log = { line -> Log.d("avn.api", line) }
    )

    @Provides
    @Singleton
    fun consentGuard(consent: ConsentManager): ConsentGuard = ConsentGuard(consent)
}
