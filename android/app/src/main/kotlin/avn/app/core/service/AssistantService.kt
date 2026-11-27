package avn.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import avn.app.R
import avn.app.core.connectivity.ConnectivityMonitor
import avn.app.core.device.BatteryMonitor
import avn.app.core.device.HeadsetMonitor
import avn.app.core.logging.JsonLinesLogger
import avn.app.core.permissions.Access
import avn.app.core.permissions.PermissionManager
import avn.app.core.state.AppEvent
import avn.app.core.state.AppStateStore
import avn.app.core.state.Health
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "assistant"
private const val NOTIFICATION_ID = 1
private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

/**
 * The one long-lived component: owns the modules and keeps running with the screen off.
 * A module failure sets that module's health; it never stops this service.
 */
@AndroidEntryPoint
class AssistantService : LifecycleService() {
    @Inject lateinit var registry: ModuleRegistry

    @Inject lateinit var permissions: PermissionManager

    @Inject lateinit var connectivity: ConnectivityMonitor

    @Inject lateinit var battery: BatteryMonitor

    @Inject lateinit var headset: HeadsetMonitor

    @Inject lateinit var store: AppStateStore

    @Inject lateinit var log: JsonLinesLogger

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        registry.modules.keys.forEach { store.dispatch(AppEvent.ModuleHealth(it, Health.OK)) }
        guarded("connectivity") { connectivity.start() }
        guarded("battery") { battery.start() }
        guarded("headset") { headset.start() }
        // Bound to this service's own lifecycle, not an activity's, so the camera (and the
        // detection pipeline below) keep running with the screen off and no activity visible --
        // the chest-mounted, hands-free use this app is built for (android/CLAUDE.md).
        guarded("camera") { registry.frameSource.bind(this) }
        guarded("detection_pipeline") { registry.detectionPipeline.start(lifecycleScope) }
        lifecycleScope.launch {
            store.state.map { it.listening }.distinctUntilChanged().collect(::holdWakeLock)
        }
        log.log("service", "created", fields = mapOf("modules" to registry.modules.size))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val types = foregroundTypes()
        if (types == 0) {
            // A typed foreground service needs at least one of its permissions; onboarding explains why.
            log.log("service", "no_permission_for_any_type")
            store.dispatch(AppEvent.ModuleHealth("service", Health.DOWN))
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
            store.dispatch(AppEvent.ModuleHealth("service", Health.OK))
            log.log("service", "foreground", fields = mapOf("types" to types, "restart" to (intent == null)))
        } catch (e: Exception) {
            // Seen when the system restarts the service from the background: camera and microphone
            // types need the app to be visible. The user reopens the app to restore them.
            log.log("service", "foreground_refused", fields = mapOf("error" to e.javaClass.simpleName))
            store.dispatch(AppEvent.ModuleHealth("service", Health.DOWN))
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        connectivity.stop()
        battery.stop()
        headset.stop()
        holdWakeLock(false)
        log.log("service", "destroyed")
        super.onDestroy()
    }

    private fun foregroundTypes(): Int {
        var types = 0
        if (permissions.isGranted(Access.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (permissions.isGranted(Access.MICROPHONE)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (permissions.isGranted(Access.LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return types
    }

    private fun notification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_running))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    /** Partial wake lock only while listening. Nothing sets AppState.listening until Phase 4. */
    private fun holdWakeLock(listening: Boolean) {
        if (listening) {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avn:listening")
                .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    private inline fun guarded(module: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(module, "failed", fields = mapOf("error" to e.javaClass.simpleName))
            store.dispatch(AppEvent.ModuleHealth(module, Health.DOWN))
        }
    }

    companion object {
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AssistantService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, AssistantService::class.java))
    }
}
