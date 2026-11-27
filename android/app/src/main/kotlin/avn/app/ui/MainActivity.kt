package avn.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import avn.app.camera.FrameSource
import avn.app.core.AppPriorityEngine
import avn.app.core.connectivity.ConnectivityMonitor
import avn.app.core.consent.ConsentManager
import avn.app.core.permissions.PermissionManager
import avn.app.core.state.AppStateStore
import avn.app.data.AlertLogDao
import avn.app.data.SettingsDao
import avn.app.vision.Detection
import avn.app.vision.Detector
import avn.app.vision.ReadTextPipeline
import avn.app.voice.FakeSpeaker
import avn.app.voice.FakeSttEngine
import avn.app.voice.FakeWakeWord
import avn.contracts.EventType
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** What the four screens need. The Debug screen drives the fakes directly, so it names them. */
class UiDeps @Inject constructor(
    val store: AppStateStore,
    val permissions: PermissionManager,
    val consent: ConsentManager,
    val settings: SettingsDao,
    val alertLog: AlertLogDao,
    val engine: EngineInput,
    val priorityEngine: AppPriorityEngine,
    private val frameSource: FrameSource,
    private val textReader: ReadTextPipeline,
    private val detector: Detector,
    val connectivity: ConnectivityMonitor,
    val wakeWord: FakeWakeWord,
    val stt: FakeSttEngine,
    val speaker: FakeSpeaker
) {
    private val detectionMutex = Mutex()

    fun bindCamera(owner: androidx.lifecycle.LifecycleOwner) = frameSource.bind(owner)

    sealed interface ObjectCapture {
        data class Found(val detections: List<Detection>) : ObjectCapture
        data object Busy : ObjectCapture
        data object CameraUnavailable : ObjectCapture
        data object NothingFound : ObjectCapture
        data object Failed : ObjectCapture
    }

    /** The UI never speaks; it asks the engine like every other producer. */
    fun say(text: String) =
        engine.submit(SpeechCandidate(text, Source.SYSTEM, EventType.SYSTEM_NOTICE, userRequested = true))

    /** WS07-07: one still -> OCR -> one spoken Info item through the engine. Nothing is stored. */
    suspend fun readTextOnce(): ReadTextPipeline.Outcome = textReader.readOnce()

    /** User-triggered YOLO snapshot. No continuous image processing or image storage. */
    suspend fun detectObjects(): ObjectCapture {
        if (!detectionMutex.tryLock()) return ObjectCapture.Busy
        var frame: avn.app.camera.Frame? = null
        return try {
            val capturedFrame = frameSource.captureStill() ?: return ObjectCapture.CameraUnavailable
            frame = capturedFrame
            val found = detector.detect(capturedFrame)
            if (found.isEmpty()) ObjectCapture.NothingFound else ObjectCapture.Found(found)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ObjectCapture.Failed
        } finally {
            frame?.bitmap?.recycle()
            detectionMutex.unlock()
        }
    }

    /** "Read again": the same spoken text, again through the engine (the only path to speech). */
    fun readText(text: String) {
        if (text.isBlank()) return
        engine.submit(
            SpeechCandidate(
                text = text,
                source = Source.CAMERA,
                type = EventType.TEXT_RESULT,
                userRequested = true
            )
        )
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var deps: UiDeps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AvnScreens(deps) } }
    }

    override fun onResume() {
        super.onResume()
        deps.bindCamera(this)
    }
}
