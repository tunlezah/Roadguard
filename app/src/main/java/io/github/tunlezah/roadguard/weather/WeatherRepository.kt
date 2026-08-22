package io.github.tunlezah.roadguard.weather

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A source of weather observations.
 *
 * Behind an interface because whether Roadguard ships weather at all depends on whether a source
 * exists that is free, works in Australia, and needs no registration, no API key and no account.
 * The evaluation is in `docs/research/weather-australia.md`; [NoWeatherSource] is the honest
 * default when nothing qualifies.
 */
interface WeatherSource {
    /** Short name shown in Settings and Diagnostics. */
    val name: String

    /** Attribution text the UI is obliged to display when this source is used. */
    val attribution: String

    suspend fun fetch(latitude: Double, longitude: Double): Result<WeatherSnapshot>
}

/**
 * The null source.
 *
 * Used when no suitable free, key-free, registration-free source is available. It fails cleanly
 * and the UI explains why, rather than Roadguard pretending to have weather or quietly
 * introducing a paid API. Weather is optional by design and is never required for recording.
 */
object NoWeatherSource : WeatherSource {
    override val name: String = "None"
    override val attribution: String = ""
    override suspend fun fetch(latitude: Double, longitude: Double): Result<WeatherSnapshot> =
        Result.failure(UnsupportedOperationException("no weather source is configured"))
}

/**
 * Optional weather, cached hard and never in the way.
 *
 * Rules this class exists to enforce:
 *
 *  * weather **never** blocks or delays recording -- it runs on the application scope, entirely
 *    independently of the recorder;
 *  * requests are rare: one on a location change of more than [MOVE_THRESHOLD_METRES], otherwise
 *    at most one per refresh interval, which the thermal engine can lengthen;
 *  * a failure is a state, not an exception: the UI says *why* weather is unavailable;
 *  * with no network, the last snapshot is served until it is clearly stale, then withdrawn --
 *    Roadguard will not burn an hour-old temperature into a video as though it were current; and
 *  * nothing but a coarse latitude/longitude is ever sent, and only when the user enables
 *    weather. See `docs/privacy.md`.
 */
class WeatherRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: StateFlow<Settings>,
    private val location: StateFlow<LocationState>,
    private val source: WeatherSource = NoWeatherSource,
) {
    private val _state = MutableStateFlow<WeatherState>(
        WeatherState.Unavailable(WeatherUnavailableReason.Disabled),
    )
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var lastFetchLatitude: Double? = null
    private var lastFetchLongitude: Double? = null
    private var refreshIntervalMinutes: Int = DEFAULT_REFRESH_MINUTES

    val sourceName: String get() = source.name
    val attribution: String get() = source.attribution
    val isSupported: Boolean get() = source !== NoWeatherSource

    fun start() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                refreshIfNeeded()
                delay(TICK_MINUTES * 60_000L)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Called by the thermal engine: heat makes network work cheaper to postpone than to do. */
    fun setRefreshInterval(minutes: Int) {
        refreshIntervalMinutes = minutes.coerceAtLeast(TICK_MINUTES)
    }

    private suspend fun refreshIfNeeded() {
        if (!settings.value.weatherEnabled) {
            _state.value = WeatherState.Unavailable(WeatherUnavailableReason.Disabled)
            return
        }
        if (!isSupported) {
            _state.value = WeatherState.Unavailable(WeatherUnavailableReason.NotSupported)
            return
        }

        val fix = location.value
        val latitude = fix.latitude
        val longitude = fix.longitude
        if (latitude == null || longitude == null) {
            _state.value = WeatherState.Unavailable(WeatherUnavailableReason.NoLocation)
            return
        }

        val current = _state.value
        val snapshot = (current as? WeatherState.Available)?.snapshot
        val now = System.currentTimeMillis()
        val moved = hasMovedFar(latitude, longitude)
        val stale = snapshot == null || snapshot.isStale(now, refreshIntervalMinutes * 60_000L)
        if (!moved && !stale) return

        if (!hasNetwork()) {
            // Keep serving a recent snapshot offline, but withdraw it once it is meaningless.
            _state.value = if (snapshot != null && !snapshot.isStale(now, OFFLINE_GRACE_MS)) {
                WeatherState.Available(snapshot)
            } else {
                WeatherState.Unavailable(WeatherUnavailableReason.Offline)
            }
            return
        }

        _state.value = if (snapshot != null) WeatherState.Available(snapshot) else WeatherState.Loading
        source.fetch(latitude, longitude)
            .onSuccess { fetched ->
                lastFetchLatitude = latitude
                lastFetchLongitude = longitude
                _state.value = WeatherState.Available(fetched)
            }
            .onFailure { throwable ->
                Log.w(TAG, "weather fetch failed", throwable)
                _state.value = if (snapshot != null) {
                    WeatherState.Available(snapshot)
                } else {
                    WeatherState.Unavailable(WeatherUnavailableReason.Failed)
                }
            }
    }

    private fun hasMovedFar(latitude: Double, longitude: Double): Boolean {
        val previousLatitude = lastFetchLatitude ?: return true
        val previousLongitude = lastFetchLongitude ?: return true
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            previousLatitude,
            previousLongitude,
            latitude,
            longitude,
            results,
        )
        return results[0] > MOVE_THRESHOLD_METRES
    }

    private fun hasNetwork(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "RoadguardWeather"

        /** How often the loop wakes; the actual fetch cadence is [refreshIntervalMinutes]. */
        const val TICK_MINUTES = 5

        const val DEFAULT_REFRESH_MINUTES = 15

        /** Refetch when the vehicle has travelled this far, roughly one weather cell. */
        const val MOVE_THRESHOLD_METRES = 20_000f

        /** How long an offline snapshot may still be shown before it is withdrawn. */
        const val OFFLINE_GRACE_MS = 3L * 60 * 60 * 1000
    }
}
