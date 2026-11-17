package avn.app.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import avn.app.core.logging.JsonLinesLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * architecture.md WS-06's "Motion state: stationary vs moving from IMU and global box flow" --
 * this covers the IMU half only. [IouTracker] feeds this into [ApproachDetector] to raise the
 * growth threshold while the user is walking, since ego-motion also grows boxes. Global-box-flow
 * fusion (the vision-side half) is not built: a single IMU signal already lets the approach
 * detector tell "walking past a parked car" from "a car is approaching a stationary user", and
 * adding a second signal without a labeled clip to validate it against would be guessing at a
 * precision number instead of measuring one.
 */
interface MotionEstimator {
    fun isWalking(): Boolean
}

@Singleton
class FakeMotionEstimator @Inject constructor() : MotionEstimator {
    @Volatile
    var walking: Boolean = false

    override fun isWalking(): Boolean = walking
}

/**
 * Walking produces a rhythmic, several-m/s^2 swing in linear acceleration magnitude at each step;
 * a stationary or slowly-panned phone stays near zero. [MotionWindow] is the pure threshold-on-
 * variance heuristic, kept Android-free so it can be unit-tested with synthetic samples; this
 * class is only the `SensorManager` plumbing around it. The window size and threshold are a
 * starting heuristic, not a Gate-2-validated number -- same caveat as [TrackerConfig]'s approach
 * thresholds before calibration.
 */
@Singleton
class AccelerometerMotionEstimator @Inject constructor(
    @ApplicationContext context: Context,
    private val log: JsonLinesLogger
) : MotionEstimator,
    SensorEventListener {
    private val window = MotionWindow()
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    @Volatile
    private var walking = false

    init {
        val registered = accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) == true
        } == true
        if (!registered) log.log("motion", "sensor_unavailable")
    }

    override fun isWalking(): Boolean = walking

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        walking = window.addSample(sqrt((x * x + y * y + z * z).toDouble()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Rolling-window variance of acceleration magnitude; `true` once [windowSize] samples are in and the variance clears [threshold]. */
class MotionWindow(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val threshold: Double = DEFAULT_THRESHOLD
) {
    private val magnitudes = ArrayDeque<Double>()

    fun addSample(magnitude: Double): Boolean {
        magnitudes.addLast(magnitude)
        if (magnitudes.size > windowSize) magnitudes.removeFirst()
        if (magnitudes.size < windowSize) return false
        val mean = magnitudes.average()
        val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size
        return variance > threshold
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 20
        const val DEFAULT_THRESHOLD = 1.0
    }
}
