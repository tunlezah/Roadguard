package io.github.tunlezah.roadguard.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * GNSS, without Google Play Services.
 *
 * Roadguard uses the platform `LocationManager` directly rather than the fused location
 * provider from Play Services. For an offline-first, privacy-first dashcam that is the better
 * choice on every axis that matters here:
 *
 *  * **it works with no SIM, no mobile data and no Wi-Fi**, which is the normal operating
 *    condition for this app -- GNSS is a satellite receiver and needs no network, though a
 *    first fix takes longer without the assistance data a network would supply;
 *  * it adds no dependency on a proprietary service, so nothing about the user's location can
 *    leave the device through a library Roadguard does not control; and
 *  * on API 31 and above the platform exposes its own `FUSED_PROVIDER`, so the sensor fusion
 *    that used to be the fused client's main advantage is available anyway.
 *
 * Speed is passed through [SpeedFilter] before it is shown or burned into video, and fix quality
 * is derived from both accuracy and satellite count so the UI can distinguish "no GPS" from
 * "still acquiring".
 */
class LocationEngine(
    private val context: Context,
    private val executor: Executor,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val speedFilter = SpeedFilter()

    private val _state = MutableStateFlow(LocationState())
    val state: StateFlow<LocationState> = _state.asStateFlow()

    private var requesting = false
    private var currentIntervalMs = DEFAULT_INTERVAL_MS
    private var satellitesVisible = 0
    private var satellitesUsed = 0

    private val listener = LocationListener { location -> onLocation(location) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesVisible = status.satelliteCount
            satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
            // Republish so "acquiring" appears as soon as satellites are visible, long before a
            // first fix; without this the UI looks broken for the first 30 seconds of a cold start.
            _state.value = _state.value.copy(
                satellitesVisible = satellitesVisible,
                satellitesUsed = satellitesUsed,
                quality = qualityFor(_state.value, satellitesVisible),
            )
        }
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Who currently wants location updates.
     *
     * The receiver used to be owned by the recorder alone, so speed and the map's own position
     * only appeared while recording -- which is not what a driver expects from a screen that is
     * showing them a map. Ownership is now shared: the engine runs while *any* client wants it, at
     * the shortest interval any of them asked for, and stops when the last one lets go. That keeps
     * "the map works as soon as you open the app" and "the thermal engine can slow the recorder's
     * updates down" from being in conflict.
     */
    enum class Client {
        /** The recorder, while a recording is running. Interval set by the thermal plan. */
        Recorder,

        /** The visible UI, so the map can centre and the speed chip can read before recording. */
        Ui,

        /** First-run setup, which shows fix acquisition as a setup step. */
        Setup,
    }

    private val requests = LocationRequests()

    /**
     * Asks for updates on [client]'s behalf, or changes the interval it wants.
     *
     * Idempotent, and safe to call from either the main thread or the recorder's scope.
     */
    fun request(client: Client, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        val effective = synchronized(requests) { requests.request(client, intervalMs) }
        applyEffectiveInterval(effective)
    }

    /** Releases [client]'s claim. Updates stop once nobody wants them. */
    fun release(client: Client) {
        val effective = synchronized(requests) { requests.release(client) }
        if (effective == null) stopUpdates() else applyEffectiveInterval(effective)
    }

    /** True when at least one client wants updates. Exposed for diagnostics. */
    val isActive: Boolean get() = synchronized(requests) { requests.isActive }

    private fun applyEffectiveInterval(intervalMs: Long) {
        if (requesting && intervalMs == currentIntervalMs) return
        startUpdates(intervalMs)
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        val manager = locationManager ?: return
        if (!hasPermission()) {
            _state.value = _state.value.copy(permissionGranted = false, quality = FixQuality.NoSignal)
            return
        }
        currentIntervalMs = intervalMs
        _state.value = _state.value.copy(permissionGranted = true)

        val provider = bestProvider(manager)
        if (provider == null) {
            _state.value = _state.value.copy(providerEnabled = false, quality = FixQuality.NoSignal)
            return
        }

        runCatching {
            if (requesting) manager.removeUpdates(listener)
            manager.requestLocationUpdates(
                provider,
                LocationRequest.Builder(intervalMs)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(intervalMs)
                    .build(),
                executor,
                listener,
            )
            if (!requesting) manager.registerGnssStatusCallback(executor, gnssCallback)
            requesting = true
            _state.value = _state.value.copy(providerEnabled = true)
            // Seed with the last known fix so the map can centre immediately instead of waiting
            // for a first fix, clearly marked stale by its age.
            manager.getLastKnownLocation(provider)?.let { onLocation(it, fromCache = true) }
        }.onFailure { Log.w(TAG, "could not request location updates", it) }
    }

    private fun stopUpdates() {
        val manager = locationManager ?: return
        currentIntervalMs = DEFAULT_INTERVAL_MS
        if (!requesting) return
        runCatching { manager.removeUpdates(listener) }
        runCatching { manager.unregisterGnssStatusCallback(gnssCallback) }
        requesting = false
        speedFilter.reset()
    }

    /**
     * Changes the rate [Client.Recorder] asks for, without dropping the current fix.
     *
     * Used by the thermal engine. It cannot slow the receiver below what another client wants,
     * which is deliberate: throttling the recorder's own updates must not blank the map the user
     * is looking at.
     */
    fun setRecorderInterval(intervalMs: Long) {
        val wanted = synchronized(requests) { requests.retune(Client.Recorder, intervalMs) } ?: return
        applyEffectiveInterval(wanted)
    }

    /** Refreshes staleness and expires a held speed, without waiting for a new fix. */
    fun tick(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val current = _state.value
        val fixElapsed = current.fixEpochMs ?: return
        val age = System.currentTimeMillis() - fixElapsed
        speedFilter.expireIfStale(nowElapsedMs)
        _state.value = current.copy(
            ageMillis = age,
            speedMetresPerSecond = speedFilter.current(nowElapsedMs),
            quality = if (age > STALE_FIX_MS) FixQuality.Searching else current.quality,
        )
    }

    private fun onLocation(location: Location, fromCache: Boolean = false) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val speed = speedFilter.accept(
            rawSpeedMps = if (location.hasSpeed()) location.speed else null,
            speedAccuracyMps = if (location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond
            } else {
                null
            },
            horizontalAccuracyMetres = if (location.hasAccuracy()) location.accuracy else null,
            atElapsedMs = nowElapsed,
        )
        val age = System.currentTimeMillis() - location.time
        val next = LocationState(
            quality = FixQuality.NoSignal,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMetres = if (location.hasAltitude()) location.altitude else null,
            speedMetresPerSecond = if (fromCache) null else speed,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
            accuracyMetres = if (location.hasAccuracy()) location.accuracy else null,
            fixEpochMs = location.time,
            ageMillis = age,
            satellitesUsed = satellitesUsed,
            satellitesVisible = satellitesVisible,
            isMock = location.isMock,
            permissionGranted = true,
            providerEnabled = true,
        )
        _state.value = next.copy(quality = qualityFor(next, satellitesVisible))
    }

    private fun bestProvider(manager: LocationManager): String? {
        // FUSED_PROVIDER exists from API 31 and minSdk is 34, so it needs no guard. It is
        // preferred because it is the platform's own sensor fusion -- no Play Services involved.
        val candidates = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        return candidates.firstOrNull { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    companion object {
        private const val TAG = "RoadguardLocation"

        /** One fix a second: what a speed readout needs, and no more. */
        const val DEFAULT_INTERVAL_MS = 1_000L

        /** A fix older than this is presented as stale rather than current. */
        const val STALE_FIX_MS = 8_000L

        /**
         * Derives fix quality.
         *
         * Deliberately conservative: a fix with 50 m accuracy is reported as weak rather than
         * shown as a position, because a dashcam overlay implying metre accuracy it does not
         * have is worse than admitting the uncertainty.
         */
        fun qualityFor(state: LocationState, satellitesVisible: Int): FixQuality {
            if (!state.permissionGranted || !state.providerEnabled) return FixQuality.NoSignal
            val accuracy = state.accuracyMetres
            val age = state.ageMillis
            return when {
                accuracy == null || state.latitude == null ->
                    if (satellitesVisible > 0) FixQuality.Searching else FixQuality.NoSignal

                age != null && age > STALE_FIX_MS -> FixQuality.Searching
                accuracy <= 8f -> FixQuality.Excellent
                accuracy <= 25f -> FixQuality.Good
                accuracy <= 75f -> FixQuality.Poor
                else -> FixQuality.Searching
            }
        }
    }
}
