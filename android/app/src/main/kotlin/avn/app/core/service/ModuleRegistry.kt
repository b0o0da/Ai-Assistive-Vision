package avn.app.core.service

import avn.app.camera.FrameSource
import avn.app.navigation.HapticCues
import avn.app.navigation.LocationSource
import avn.app.navigation.Navigation
import avn.app.pipeline.DetectionPipeline
import avn.app.screen.NotificationSource
import avn.app.screen.ScreenActions
import avn.app.screen.ScreenAi
import avn.app.screen.ScreenCapture
import avn.app.screen.ScreenReader
import avn.app.sos.Sos
import avn.app.vision.Detector
import avn.app.vision.OcrEngine
import avn.app.vision.Tracker
import avn.app.voice.ActionRouter
import avn.app.voice.EmergencyKeywordDetector
import avn.app.voice.IntentRouter
import avn.app.voice.SttEngine
import avn.app.voice.WakeWord
import avn.engine.EngineInput
import avn.engine.SpeechOutput
import javax.inject.Inject
import javax.inject.Singleton

/** Every feature seam, owned by AssistantService. The names are the keys of AppState.moduleHealth. */
@Singleton
class ModuleRegistry @Inject constructor(
    val engineInput: EngineInput,
    val speechOutput: SpeechOutput,
    val frameSource: FrameSource,
    val detector: Detector,
    val tracker: Tracker,
    val ocrEngine: OcrEngine,
    val wakeWord: WakeWord,
    val emergencyKeywordDetector: EmergencyKeywordDetector,
    val sttEngine: SttEngine,
    val intentRouter: IntentRouter,
    val actionRouter: ActionRouter,
    val screenReader: ScreenReader,
    val screenActions: ScreenActions,
    val screenCapture: ScreenCapture,
    val screenAi: ScreenAi,
    val notificationSource: NotificationSource,
    val locationSource: LocationSource,
    val navigation: Navigation,
    val hapticCues: HapticCues,
    val sos: Sos,
    val detectionPipeline: DetectionPipeline
) {
    val modules: Map<String, Any> = mapOf(
        "engine" to engineInput,
        "speaker" to speechOutput,
        "camera" to frameSource,
        "detector" to detector,
        "tracker" to tracker,
        "detection_pipeline" to detectionPipeline,
        "ocr" to ocrEngine,
        "wakeword" to wakeWord,
        "emergency_keyword" to emergencyKeywordDetector,
        "stt" to sttEngine,
        "intent" to intentRouter,
        "router" to actionRouter,
        "screen_reader" to screenReader,
        "screen_actions" to screenActions,
        "screen_capture" to screenCapture,
        "screen_ai" to screenAi,
        "notifications" to notificationSource,
        "location" to locationSource,
        "navigation" to navigation,
        "haptics" to hapticCues,
        "sos" to sos
    )
}
