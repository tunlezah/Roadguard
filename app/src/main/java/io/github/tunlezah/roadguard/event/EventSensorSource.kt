package io.github.tunlezah.roadguard.event

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Feeds the accelerometer into [ImpactDetector].
 *
 * ### Gravity handling
 *
 * `TYPE_LINEAR_ACCELERATION` and `TYPE_GRAVITY` are virtual sensors the platform derives, and on
 * budget hardware without a gyroscope they may be absent or poor. So Roadguard prefers them when
 * present and otherwise derives both itself from the raw accelerometer with a first-order
 * low-pass filter: the slow component is gravity, the remainder is linear acceleration. That
 * fallback is what keeps event detection working on a phone with no gyroscope, which is common in
 * the baseline device's class.
 *
 * ### Rate
 *
 * [SAMPLE_PERIOD_US] asks for 100 Hz. That is fast enough to see the shape of an impact (which
 * lasts tens of milliseconds) and slow enough to avoid the `HIGH_SAMPLING_RATE_SENSORS`
 * permission, which is only required above 200 Hz. The platform is free to deliver slower, and
 * the detector works from the timestamps rather than assuming a rate.
 */
class EventSensorSource(context: Context) {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearAcceleration: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _samples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 256)
    val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

    private val _available = MutableStateFlow(SensorAvailability())
    val available: StateFlow<SensorAvailability> = _available.asStateFlow()

    private val gravityEstimate = FloatArray(3)
    private var gravitySeeded = false
    private var latestLinear: FloatArray? = null
    private var listening = false

    val hasGyroscope: Boolean get() = gyroscope != null
    val hasAccelerometer: Boolean get() = accelerometer != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> {
                    gravityEstimate[0] = event.values[0]
                    gravityEstimate[1] = event.values[1]
                    gravityEstimate[2] = event.values[2]
                    gravitySeeded = true
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    latestLinear = event.values.copyOf(3)
                    emit(event.timestamp)
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    if (linearAcceleration == null || gravitySensor == null) {
                        updateDerivedGravity(event.values)
                        latestLinear = floatArrayOf(
                            event.values[0] - gravityEstimate[0],
                            event.values[1] - gravityEstimate[1],
                            event.values[2] - gravityEstimate[2],
                        )
                        emit(event.timestamp)
                    } else if (!gravitySeeded) {
                        // Seed the estimate from the first raw reading so the very first samples
                        // are not dominated by a gravity estimate of zero.
                        updateDerivedGravity(event.values)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val manager = sensorManager ?: return
        if (listening) return
        var registered = false
        // Register the virtual sensors when the platform provides them, and always register the
        // raw accelerometer: it is the fallback and it is what seeds the gravity estimate.
        linearAcceleration?.let {
            registered = manager.registerListener(listener, it, SAMPLE_PERIOD_US) || registered
        }
        gravitySensor?.let {
            registered = manager.registerListener(listener, it, SAMPLE_PERIOD_US) || registered
        }
        accelerometer?.let {
            registered = manager.registerListener(listener, it, SAMPLE_PERIOD_US) || registered
        }
        listening = registered
        _available.value = SensorAvailability(
            accelerometer = accelerometer != null,
            gyroscope = gyroscope != null,
            linearAcceleration = linearAcceleration != null,
            gravity = gravitySensor != null,
            registered = registered,
            maxRateHz = accelerometer?.minDelay?.takeIf { it > 0 }?.let { 1_000_000 / it },
        )
        if (!registered) Log.w(TAG, "no motion sensors could be registered; event detection is unavailable")
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(listener)
        listening = false
        gravitySeeded = false
        latestLinear = null
        _available.value = _available.value.copy(registered = false)
    }

    private fun updateDerivedGravity(raw: FloatArray) {
        if (!gravitySeeded) {
            gravityEstimate[0] = raw[0]
            gravityEstimate[1] = raw[1]
            gravityEstimate[2] = raw[2]
            gravitySeeded = true
            return
        }
        for (axis in 0..2) {
            gravityEstimate[axis] =
                GRAVITY_SMOOTHING * gravityEstimate[axis] + (1f - GRAVITY_SMOOTHING) * raw[axis]
        }
    }

    private fun emit(timestampNanos: Long) {
        val linear = latestLinear ?: return
        _samples.tryEmit(
            SensorSample(
                elapsedRealtimeNanos = timestampNanos,
                linearX = linear[0],
                linearY = linear[1],
                linearZ = linear[2],
                gravityX = gravityEstimate[0],
                gravityY = gravityEstimate[1],
                gravityZ = gravityEstimate[2],
            ),
        )
    }

    companion object {
        private const val TAG = "RoadguardSensors"

        /**
         * 10 ms, i.e. 100 Hz.
         *
         * Above 200 Hz Android requires the `HIGH_SAMPLING_RATE_SENSORS` permission; Roadguard
         * stays below that deliberately, because a permission prompt for a marginal improvement
         * in impact-shape resolution is a bad trade for a privacy-first app.
         */
        const val SAMPLE_PERIOD_US = 10_000

        /**
         * Low-pass coefficient for the derived gravity estimate.
         *
         * 0.98 at 100 Hz gives a time constant of roughly half a second: slow enough to ignore
         * an impact, fast enough to follow the phone being re-seated in its cradle.
         */
        const val GRAVITY_SMOOTHING = 0.98f
    }
}

data class SensorAvailability(
    val accelerometer: Boolean = false,
    val gyroscope: Boolean = false,
    val linearAcceleration: Boolean = false,
    val gravity: Boolean = false,
    val registered: Boolean = false,
    val maxRateHz: Int? = null,
) {
    val canDetectEvents: Boolean get() = accelerometer && registered

    fun describe(): String = buildList {
        add("accelerometer: ${yesNo(accelerometer)}")
        add("gyroscope: ${yesNo(gyroscope)}")
        add("linear acceleration: ${yesNo(linearAcceleration)}")
        add("gravity: ${yesNo(gravity)}")
        maxRateHz?.let { add("max rate: $it Hz") }
    }.joinToString("; ")

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"
}
