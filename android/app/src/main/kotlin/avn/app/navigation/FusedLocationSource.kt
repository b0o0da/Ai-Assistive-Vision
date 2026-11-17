package avn.app.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import avn.app.core.logging.JsonLinesLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The phone's own fused location provider -- no network call, no API key. [LocationManager]
 * (not Play Services' FusedLocationProviderClient) so the app has no Play Services dependency.
 */
@Singleton
class FusedLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: JsonLinesLogger
) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFix() = LocationFix(latitude, longitude, accuracy, time)

    override fun locations(): Flow<LocationFix> = callbackFlow {
        if (!hasPermission()) {
            log.log("location", "permission_denied")
            close()
            return@callbackFlow
        }
        val listener = LocationListener { location -> trySend(location.toFix()) }
        val requested = try {
            manager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 2_000L, 3f, listener, context.mainLooper)
            true
        } catch (error: SecurityException) {
            log.log("location", "updates_failed", fields = mapOf("error" to error.javaClass.simpleName))
            false
        }
        if (!requested) {
            close()
            return@callbackFlow
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    override suspend fun lastKnown(): LocationFix? {
        if (!hasPermission()) {
            log.log("location", "permission_denied")
            return null
        }
        val fresh = runCatching { freshFix() }.getOrNull()
        if (fresh != null) return fresh
        return try {
            manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)?.toFix()
        } catch (error: SecurityException) {
            log.log("location", "last_known_failed", fields = mapOf("error" to error.javaClass.simpleName))
            null
        }
    }

    /** A fresh fix with its own cancellation, not a request/callback pair this class must track. */
    private suspend fun freshFix(): LocationFix? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            manager.getCurrentLocation(LocationManager.FUSED_PROVIDER, signal, context.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location?.toFix())
            }
        } catch (error: SecurityException) {
            log.log("location", "current_location_failed", fields = mapOf("error" to error.javaClass.simpleName))
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
