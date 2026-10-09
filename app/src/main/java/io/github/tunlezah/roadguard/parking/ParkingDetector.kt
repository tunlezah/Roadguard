package io.github.tunlezah.roadguard.parking

import io.github.tunlezah.roadguard.map.PlaceRanking
import java.util.Locale
import kotlin.math.max

/**
 * A GNSS fix, as the parking logic sees it.
 *
 * @param atMs when the fix arrived, in milliseconds on the elapsed-realtime clock.
 * @param accuracyMetres the fix's horizontal accuracy, or null when it reported none.
 * @param speedMetresPerSecond the *filtered* speed (see [io.github.tunlezah.roadguard.location.SpeedFilter]):
 *   poor fixes already discarded, crawl-speed wander already clamped to zero. Null when no
 *   trustworthy speed was available.
 */
data class ParkingFix(
    val atMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float?,
    val speedMetresPerSecond: Float?,
)

/** What showed that the vehicle moved. */
enum class MovementCue {
    /** The accelerometer: sustained shaking, or the phone turning. */
    Motion,

    /** GNSS speed, on consecutive fixes. */
    Speed,

    /** GNSS position, further than drift explains, on consecutive fixes. */
    Position,

    /** The accelerometer went silent, so that interval could not be vouched for. */
    SensorGap,
}

/**
 * The most recent evidence of movement, kept for Diagnostics: a stillness detector whose verdict
 * cannot be explained cannot be tuned.
 *
 * @param value the measurement behind it: m/s^2 for [MovementCue.Motion], m/s for
 *   [MovementCue.Speed], metres for [MovementCue.Position], seconds for [MovementCue.SensorGap].
 */
data class Movement(val cue: MovementCue, val atMs: Long, val value: Double) {
    fun describe(): String = when (cue) {
        MovementCue.Motion -> String.format(Locale.ROOT, "sustained motion (%.2f m/s²)", value)
        MovementCue.Speed -> String.format(Locale.ROOT, "GPS speed %.0f km/h", value * 3.6)
        MovementCue.Position -> String.format(Locale.ROOT, "GPS position moved %.0f m", value)
        MovementCue.SensorGap -> String.format(Locale.ROOT, "no motion data for %.0f s", value)
    }
}

/**
 * Every threshold the parking logic uses, in one place.
 *
 * ### Honesty about the numbers
 *
 * None of these has been measured in a car; Roadguard has never run on a device (see
 * `docs/testing.md`). Each is reasoned from sensor physics -- a phone accelerometer's own noise is a
 * few hundredths of a m/s^2, a car on the move shakes a cradled phone by tenths, a person walking
 * shakes it by whole m/s^2 -- and from how consumer GNSS behaves when parked. They are gathered
 * here, as [io.github.tunlezah.roadguard.event.ImpactDetector.Tuning] gathers its own, so that real
 * traces can replace them wholesale.
 *
 * ### Which way to be wrong
 *
 * Pausing a recording that should have carried on loses footage; recording a car that has stopped
 * only costs battery. So the rules lean towards "moving": stillness has to be *observed* for the
 * whole period, never assumed from silence; any one check can veto it; and the evidence needed to
 * resume is weaker than anything that could fake stillness.
 */
data class ParkingTuning(
    // ── Accelerometer: has the vehicle stopped? ──────────────────────────────────────────────
    /** A window thinner than this is not judged at all. */
    val minSamplesPerWindow: Int = 5,

    /**
     * Below this vibration, in m/s^2, a window is still. A phone accelerometer's noise floor is
     * roughly 0.01-0.05 m/s^2; a cradled phone in a car on the move reads 0.2-1 and more.
     */
    val stillVibration: Float = 0.10f,

    /** The phone turning by more than this between consecutive windows is not stillness. */
    val stillTurnDegrees: Float = 3f,

    /**
     * Movement is [sustainedActiveWindows] restless windows among the last [sustainedWindows]. A door
     * closing or a gust rocking the car is a window or two and changes nothing; a car pulling away
     * is restless from then on.
     */
    val sustainedWindows: Int = 10,
    val sustainedActiveWindows: Int = 5,

    /** A silence from the accelerometer longer than this means that interval was not observed. */
    val maxSensorGapMs: Long = 5_000L,

    // ── GNSS: a veto on stillness ────────────────────────────────────────────────────────────
    /** Filtered speed at or above this, in m/s (about 9 km/h), is movement. */
    val movingSpeed: Float = 2.5f,

    /** Positions vaguer than this, in metres, are no evidence either way and are ignored. */
    val maxFixAccuracyMetres: Float = 30f,

    /**
     * A fix has moved when it is further from where the still period began than this, in metres...
     */
    val minDriftRadiusMetres: Double = 40.0,

    /** ...and further than the two fixes' accuracies plus this margin, whichever is larger. */
    val driftMarginMetres: Double = 25.0,

    /** GNSS evidence counts only when this many consecutive fixes agree: one bad fix is drift. */
    val confirmingFixes: Int = 2,

    // ── Driving off again, while parked ──────────────────────────────────────────────────────
    /** The phone must still sit within this many degrees of how it sat when the car parked. */
    val poseToleranceDegrees: Float = 25f,

    /** A car moving off shakes a cradled phone by at least this much, in m/s^2... */
    val drivingMinVibration: Float = 0.12f,

    /**
     * ...and by less than this: above it is a person walking with the phone, not a car. A cradled
     * phone reads well under it even on broken bitumen at car-park speeds; a phone walked about in
     * a pocket typically reads 2-4 m/s^2.
     */
    val drivingMaxVibration: Float = 2.0f,

    /** A mount does not turn by more than this between windows; a hand does. */
    val drivingTurnDegrees: Float = 6f,

    /** Driving is [drivingActiveWindows] such windows among the last [drivingWindows]. */
    val drivingWindows: Int = 8,
    val drivingActiveWindows: Int = 5,

    /** Filtered speed at or above this, in m/s (about 18 km/h), on [drivingFixes] consecutive fixes. */
    val drivingSpeed: Float = 5.0f,
    val drivingFixes: Int = 3,
)

/**
 * How long the vehicle has *verifiably* not moved.
 *
 * ### Three checks, and the first has to be seen
 *
 *  * **The accelerometer** must show the phone still -- no sustained shaking, no turning -- for the
 *    whole period. It is the one check that must positively pass: with no motion data the vehicle
 *    is never judged still however quiet GNSS looks, because seeing nothing is not the same as
 *    nothing happening. A silence in the data restarts the period.
 *  * **GNSS speed** must not show the vehicle moving.
 *  * **GNSS position** must stay within what drift explains of where the still period began.
 *
 * GNSS is a veto rather than a requirement: there is no fix at all in an underground car park,
 * where the accelerometer has to be enough. Nor is GNSS ever trusted on a single fix -- a parked
 * receiver wanders by tens of metres and reports a few km/h now and then -- so speed and position
 * each need consecutive fixes that agree, and the radius grows with the fixes' own accuracy.
 *
 * Pure: times are passed in, in milliseconds on the elapsed-realtime clock. Windows are judged by
 * when they arrived, and only their sensor timestamps are used to find gaps between them, so a
 * device whose sensor clock is offset from the system's still behaves.
 */
class StillnessTracker(private val tuning: ParkingTuning = ParkingTuning()) {

    private var stillSinceMs: Long? = null
    private var lastWindowAtMs: Long? = null
    private var lastWindowEndMs: Long? = null
    private var previousGravity: Direction? = null
    private val recentRestless = ArrayDeque<Boolean>()

    // A decaying sum of still windows' gravity directions; its direction is the pose.
    private var poseX = 0.0
    private var poseY = 0.0
    private var poseZ = 0.0

    private var anchor: ParkingFix? = null
    private var fastFixes = 0
    private var farFixes = 0

    /** The latest window's vibration in m/s^2, for Diagnostics. */
    var lastVibration: Float? = null
        private set

    /** The most recent evidence of movement, for Diagnostics. */
    var lastMovement: Movement? = null
        private set

    /**
     * How gravity points through the phone while it sits still -- the pose it is parked in -- or
     * null before any still window has been seen.
     */
    val pose: Direction? get() = Direction.of(poseX, poseY, poseZ)

    /** True while windows are arriving, i.e. the accelerometer is actually being observed. */
    fun observing(nowMs: Long): Boolean = lastWindowAtMs?.let { nowMs - it <= tuning.maxSensorGapMs } == true

    fun onWindow(window: MotionWindow, atMs: Long) {
        if (window.samples < tuning.minSamplesPerWindow) return
        val previousEnd = lastWindowEndMs
        if (previousEnd != null && window.startMs - previousEnd > tuning.maxSensorGapMs) {
            // Whatever happened in the silence was not seen, so it cannot be counted as still.
            restart(Movement(MovementCue.SensorGap, atMs, (window.startMs - previousEnd) / 1_000.0))
            recentRestless.clear()
            previousGravity = null
        }
        lastWindowEndMs = window.endMs
        lastWindowAtMs = atMs
        lastVibration = window.vibration

        val gravity = window.gravity
        val previous = previousGravity
        if (gravity != null) previousGravity = gravity
        val turned = if (previous != null && gravity != null) previous.degreesTo(gravity) else 0f
        val restless = window.vibration >= tuning.stillVibration || turned >= tuning.stillTurnDegrees

        recentRestless.addLast(restless)
        while (recentRestless.size > tuning.sustainedWindows) recentRestless.removeFirst()
        if (recentRestless.count { it } >= tuning.sustainedActiveWindows) {
            // The window stays in the history, so a car still on the move keeps the period from
            // starting until its motion has properly died away.
            restart(Movement(MovementCue.Motion, atMs, window.vibration.toDouble()))
            return
        }
        // A restless second may sit inside a still period -- a door closing -- but never begins
        // one: stillness has to be seen. Without this, the first few seconds of a drive, before
        // the history is long enough to call the motion sustained, would count as still.
        if (restless) return
        if (gravity != null) learnPose(gravity)
        if (stillSinceMs == null) stillSinceMs = atMs
    }

    fun onFix(fix: ParkingFix) {
        val speed = fix.speedMetresPerSecond
        fastFixes = if (speed != null && speed >= tuning.movingSpeed) fastFixes + 1 else 0
        if (speed != null && fastFixes >= tuning.confirmingFixes) {
            restart(Movement(MovementCue.Speed, fix.atMs, speed.toDouble()))
            anchor = fix.takeIf { it.isPrecise() }
            farFixes = 0
            return
        }

        if (!fix.isPrecise()) return
        val from = anchor
        if (from == null) {
            anchor = fix
            farFixes = 0
            return
        }
        val metres = distanceMetres(from, fix)
        if (metres > driftRadiusMetres(from, fix)) {
            farFixes++
            if (farFixes >= tuning.confirmingFixes) {
                restart(Movement(MovementCue.Position, fix.atMs, metres))
                anchor = fix
                farFixes = 0
            }
        } else {
            farFixes = 0
            // A much sharper fix of the same spot is a better place to measure drift from.
            if (fix.accuracyOrMax() < from.accuracyOrMax() / 2) anchor = fix
        }
    }

    /**
     * How long, up to [nowMs], the vehicle has verifiably been still; 0 while it is moving, and
     * while the accelerometer is not being observed at all.
     */
    fun stillForMs(nowMs: Long): Long {
        val since = stillSinceMs ?: return 0L
        if (!observing(nowMs)) return 0L
        return (nowMs - since).coerceAtLeast(0L)
    }

    private fun restart(movement: Movement) {
        stillSinceMs = null
        lastMovement = movement
    }

    private fun learnPose(gravity: Direction) {
        poseX = poseX * POSE_MEMORY + gravity.x
        poseY = poseY * POSE_MEMORY + gravity.y
        poseZ = poseZ * POSE_MEMORY + gravity.z
    }

    private fun ParkingFix.isPrecise(): Boolean =
        accuracyMetres != null && accuracyMetres <= tuning.maxFixAccuracyMetres

    private fun driftRadiusMetres(from: ParkingFix, to: ParkingFix): Double = max(
        tuning.minDriftRadiusMetres,
        from.accuracyOrMax().toDouble() + to.accuracyOrMax() + tuning.driftMarginMetres,
    )

    private companion object {
        /** How much of the previous pose survives each still window: a memory of about ten seconds. */
        const val POSE_MEMORY = 0.9
    }
}

/**
 * Watches a parked vehicle for the moment it drives off again.
 *
 * ### What counts as driving
 *
 *  * **The phone shaken the way a moving car shakes it, while it still sits the way it sat when
 *    the car parked.** Sustained -- [ParkingTuning.drivingActiveWindows] of the last
 *    [ParkingTuning.drivingWindows] seconds -- and moderate: more than sensor noise, less than a
 *    person walking. The phone must not be turning, and gravity must still point within
 *    [ParkingTuning.poseToleranceDegrees] of the parked pose. That pose test is what keeps a driver
 *    who took the phone with them from starting a recording in their pocket.
 *  * **GNSS speed** at driving pace on consecutive fixes, whatever the pose. That needs something
 *    else to have GNSS running -- the app on screen, typically -- because Roadguard does not turn
 *    GNSS on itself while parked: not running it is a large part of what parking saves.
 *
 * A door closing, a gust, a truck going past is a second or two and does not count. Someone
 * climbing in and starting the engine may well count, and that is the right mistake to make: a
 * driver who has started the car is about to leave, and recording a minute early costs nothing
 * but battery.
 *
 * Once it has seen driving it stays tripped; the recorder resumes and discards it.
 *
 * Pure: times are passed in, in milliseconds on the elapsed-realtime clock.
 *
 * @param parkedPose how gravity pointed through the phone while the car stood still, or null if that
 *   was never seen, in which case only GNSS can show driving.
 */
class MovementWatch(
    private val parkedPose: Direction?,
    private val tuning: ParkingTuning = ParkingTuning(),
) {
    private val recentDriving = ArrayDeque<Boolean>()
    private var lastWindowAtMs: Long? = null
    private var lastWindowEndMs: Long? = null
    private var previousGravity: Direction? = null
    private var fastFixes = 0

    /** The latest window's vibration in m/s^2, for Diagnostics. */
    var lastVibration: Float? = null
        private set

    /** What showed the vehicle driving off, once something has. */
    var movement: Movement? = null
        private set

    val isMoving: Boolean get() = movement != null

    /** True while windows are arriving, i.e. the accelerometer is actually being watched. */
    fun observing(nowMs: Long): Boolean = lastWindowAtMs?.let { nowMs - it <= tuning.maxSensorGapMs } == true

    fun onWindow(window: MotionWindow, atMs: Long) {
        if (isMoving || window.samples < tuning.minSamplesPerWindow) return
        val previousEnd = lastWindowEndMs
        if (previousEnd != null && window.startMs - previousEnd > tuning.maxSensorGapMs) {
            // Seconds either side of a silence are not "sustained" together.
            recentDriving.clear()
            previousGravity = null
        }
        lastWindowAtMs = atMs
        lastWindowEndMs = window.endMs
        lastVibration = window.vibration

        val gravity = window.gravity
        val previous = previousGravity
        if (gravity != null) previousGravity = gravity
        val turned = if (previous != null && gravity != null) previous.degreesTo(gravity) else 0f
        val inMount = parkedPose != null && gravity != null &&
            parkedPose.degreesTo(gravity) <= tuning.poseToleranceDegrees
        val driving = inMount &&
            turned < tuning.drivingTurnDegrees &&
            window.vibration >= tuning.drivingMinVibration &&
            window.vibration < tuning.drivingMaxVibration

        recentDriving.addLast(driving)
        while (recentDriving.size > tuning.drivingWindows) recentDriving.removeFirst()
        if (recentDriving.count { it } >= tuning.drivingActiveWindows) {
            movement = Movement(MovementCue.Motion, atMs, window.vibration.toDouble())
        }
    }

    fun onFix(fix: ParkingFix) {
        if (isMoving) return
        val speed = fix.speedMetresPerSecond
        fastFixes = if (speed != null && speed >= tuning.drivingSpeed) fastFixes + 1 else 0
        if (speed != null && fastFixes >= tuning.drivingFixes) {
            movement = Movement(MovementCue.Speed, fix.atMs, speed.toDouble())
        }
    }
}

private fun ParkingFix.accuracyOrMax(): Float = accuracyMetres ?: Float.MAX_VALUE

private fun distanceMetres(from: ParkingFix, to: ParkingFix): Double =
    PlaceRanking.distanceKm(from.latitude, from.longitude, to.latitude, to.longitude) * 1_000.0
