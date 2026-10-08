package io.github.tunlezah.roadguard.event

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
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
 *
 * ### Power
 *
 * Sampling at 100 Hz does not require *waking up* at 100 Hz. Events are requested with a
 * [MAX_REPORT_LATENCY_US] batching window, so a sensor hub with a FIFO -- almost every phone --
 * collects them in hardware and hands them over a few times a second instead of one at a time.
 * Every sample keeps its own timestamp, and the detectors work only from timestamps, so detection
 * is unchanged; an impact is simply recognised up to a quarter of a second later, against a
 * protection window measured in tens of seconds. Delivery is on a background thread of its own,
 * not the main thread that also draws the preview and the map.
 *
 * And when the platform provides both virtual sensors, the raw accelerometer is not registered at
 * all: gravity arrives directly, so the raw stream would be a third 100 Hz stream delivered only
 * to be thrown away.
 *
 * ### Watching a parked car
 *
 * [Mode.MotionWatch] serves the parked recorder, which only needs to notice the car moving off. It
 * registers the raw accelerometer alone, at 25 Hz with a one-second batch: the platform's virtual
 * sensors are fused from the gyroscope on phones that have one, and a gyroscope draws far more than
 * an accelerometer. Gravity is derived in both modes the same way, from a filter whose time
 * constant is set in seconds rather than in samples, so the slower rate changes nothing about it.
 */
class EventSensorSource(context: Context) {

    /** What the motion data is for, which decides the sensors and the rate. */
    enum class Mode {
        /** Impact and brake detection, and stillness while recording: 100 Hz, fused sensors preferred. */
        Events,

        /** A parked recorder watching for the car to move off: the raw accelerometer at 25 Hz. */
        MotionWatch,
    }

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

    // Touched only on the delivery thread while listening, and reset before registering.
    private val gravityEstimate = FloatArray(3)
    private var gravitySeeded = false
    private var lastRawNanos = 0L
    private var latestLinear: FloatArray? = null

    @Volatile
    private var listening = false
    private var mode = Mode.Events
    private var deliveryThread: HandlerThread? = null

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

                // Only registered when the virtual pair is not in use, so this is the whole signal.
                Sensor.TYPE_ACCELEROMETER -> {
                    updateDerivedGravity(event.values, event.timestamp)
                    latestLinear = floatArrayOf(
                        event.values[0] - gravityEstimate[0],
                        event.values[1] - gravityEstimate[1],
                        event.values[2] - gravityEstimate[2],
                    )
                    emit(event.timestamp)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Starts delivering [samples] in [mode]. Starting in the mode already running does nothing;
     * starting in the other one switches over.
     */
    fun start(mode: Mode = Mode.Events) {
        val manager = sensorManager ?: return
        if (listening) {
            if (this.mode == mode) return
            stop()
        }
        this.mode = mode
        gravitySeeded = false
        lastRawNanos = 0L
        latestLinear = null
        val thread = HandlerThread("roadguard-sensors").apply { start() }
        val handler = Handler(thread.looper)
        val periodUs = if (mode == Mode.Events) SAMPLE_PERIOD_US else WATCH_SAMPLE_PERIOD_US
        val latencyUs = if (mode == Mode.Events) MAX_REPORT_LATENCY_US else WATCH_MAX_REPORT_LATENCY_US
        fun register(sensor: Sensor): Boolean =
            manager.registerListener(listener, sensor, periodUs, latencyUs, handler)

        // Prefer the platform's virtual pair. Both or neither: linear acceleration without the
        // matching gravity would feed the detector a zero gravity vector for the whole session.
        // Never while watching a parked car, where the gyroscope behind them would cost more than
        // everything else that is running.
        val linear = linearAcceleration.takeIf { mode == Mode.Events }
        val gravity = gravitySensor.takeIf { mode == Mode.Events }
        val virtualPair = linear != null && gravity != null && register(linear) &&
            (register(gravity) || run { manager.unregisterListener(listener, linear); false })
        // Otherwise derive both from the raw accelerometer, which works with no gyroscope at all.
        val registered = virtualPair || accelerometer?.let { register(it) } == true

        if (registered) {
            deliveryThread = thread
        } else {
            thread.quitSafely()
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
        // quitSafely lets an in-flight batch finish on its own thread; the next start() resets
        // the derived state before anything is registered again.
        deliveryThread?.quitSafely()
        deliveryThread = null
        _available.value = _available.value.copy(registered = false)
    }

    private fun updateDerivedGravity(raw: FloatArray, timestampNanos: Long) {
        val previousNanos = lastRawNanos
        lastRawNanos = timestampNanos
        if (!gravitySeeded) {
            gravityEstimate[0] = raw[0]
            gravityEstimate[1] = raw[1]
            gravityEstimate[2] = raw[2]
            gravitySeeded = true
            return
        }
        val smoothing = gravitySmoothing(timestampNanos - previousNanos)
        for (axis in 0..2) {
            gravityEstimate[axis] = smoothing * gravityEstimate[axis] + (1f - smoothing) * raw[axis]
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
         * How long the sensor hub may hold samples before delivering them: 250 ms.
         *
         * Cuts deliveries from one per sample (up to 200 a second across two sensors) to about
         * four a second on hardware with a FIFO, and changes nothing on hardware without one. The
         * detectors run on sample timestamps, so the only effect is recognising an impact at most
         * this much later.
         */
        const val MAX_REPORT_LATENCY_US = 250_000

        /** 40 ms, i.e. 25 Hz: [Mode.MotionWatch]. A car pulling away shakes a phone for seconds. */
        const val WATCH_SAMPLE_PERIOD_US = 40_000

        /**
         * One second of batching in [Mode.MotionWatch]: the parked watch judges whole seconds, so
         * there is nothing to gain from hearing about them sooner.
         */
        const val WATCH_MAX_REPORT_LATENCY_US = 1_000_000

        /**
         * Time constant of the derived gravity estimate: half a second, slow enough to ignore an
         * impact and fast enough to follow the phone being re-seated in its cradle.
         *
         * It used to be a fixed per-sample coefficient of 0.98, which is half a second only at
         * exactly 100 Hz. A platform delivering more slowly than asked -- or the 25 Hz parked
         * watch -- would have stretched it to two seconds and more.
         */
        const val GRAVITY_TIME_CONSTANT_NANOS = 500_000_000L

        /**
         * The low-pass coefficient for a sample [elapsedNanos] after the previous one: 0.98 at
         * 100 Hz, about 0.93 at 25 Hz. A gap longer than the time constant mostly forgets the old
         * estimate, as it should; a nonsensical interval keeps it unchanged.
         */
        fun gravitySmoothing(elapsedNanos: Long): Float {
            if (elapsedNanos <= 0L) return 1f
            return (GRAVITY_TIME_CONSTANT_NANOS.toDouble() / (GRAVITY_TIME_CONSTANT_NANOS + elapsedNanos)).toFloat()
        }
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
