package avn.app.navigation

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import avn.app.core.logging.JsonLinesLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PULSE_DURATION_MS = 200L

/** A single vibration pulse for a step change or reroute. Directional patterns are a Should, not built here. */
interface HapticCues {
    fun pulse()
}

@Singleton
class FakeHapticCues @Inject constructor(private val log: JsonLinesLogger) : HapticCues {
    override fun pulse() = log.log("haptics", "fake_pulse")
}

@Singleton
class VibratorHapticCues @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: JsonLinesLogger
) : HapticCues {
    override fun pulse() {
        try {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(PULSE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (error: SecurityException) {
            log.log("haptics", "pulse_failed", fields = mapOf("error" to error.javaClass.simpleName))
        }
    }
}
