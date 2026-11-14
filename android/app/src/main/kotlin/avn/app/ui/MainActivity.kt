package avn.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import avn.app.core.connectivity.ConnectivityMonitor
import avn.app.core.consent.ConsentManager
import avn.app.core.permissions.PermissionManager
import avn.app.core.state.AppStateStore
import avn.app.data.AlertLogDao
import avn.app.data.SettingsDao
import avn.app.camera.FrameSource
import avn.app.vision.OcrCapture
import avn.app.vision.OcrEngine
import avn.app.vision.Detector
import avn.app.vision.Detection
import avn.app.voice.FakeSpeaker
import avn.app.voice.FakeSttEngine
import avn.app.voice.FakeWakeWord
import avn.contracts.EventType
import avn.contracts.Source
import avn.contracts.SpeechCandidate
import avn.engine.EngineInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** What the four screens need. The Debug screen drives the fakes directly, so it names them. */
class UiDeps @Inject constructor(
    val store: AppStateStore,
    val permissions: PermissionManager,
    val consent: ConsentManager,
    val settings: SettingsDao,
    val alertLog: AlertLogDao,
    val engine: EngineInput,
    private val frameSource: FrameSource,
    private val ocrEngine: OcrEngine,
    private val detector: Detector,
    val connectivity: ConnectivityMonitor,
    val wakeWord: FakeWakeWord,
    val stt: FakeSttEngine,
    val speaker: FakeSpeaker
) {
    private val ocrMutex = Mutex()
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

    /** A user-triggered, single-frame OCR action. Frames and recognized text stay in memory only. */
    suspend fun captureText(): OcrCapture {
        if (!ocrMutex.tryLock()) return OcrCapture.Busy
        var frame: avn.app.camera.Frame? = null
        return try {
            val capturedFrame = frameSource.captureStill() ?: return OcrCapture.CameraUnavailable
            frame = capturedFrame
            val result = ocrEngine.read(capturedFrame)
            if (result.text.isBlank()) OcrCapture.NoText else OcrCapture.Found(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OcrCapture.Failed
        } finally {
            frame?.bitmap?.recycle()
            ocrMutex.unlock()
        }
    }

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

    /** The engine remains the only path from a producer to speech output. */
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
