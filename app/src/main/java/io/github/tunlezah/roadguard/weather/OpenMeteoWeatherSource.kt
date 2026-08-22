package io.github.tunlezah.roadguard.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Weather from Open-Meteo.
 *
 * ### Why this source, and why not the Bureau of Meteorology
 *
 * The specification asks for an Australian government source where that is legally and technically
 * appropriate. It is not. The Bureau of Meteorology actively blocks automated access, and says so in
 * the response it serves to one: *"The Bureau of Meteorology website does not support web scraping:
 * if you are trying to access Bureau data through automated means, you should stop."* It offers an
 * anonymous FTP channel of bulk product files, and a **Registered User service to which charges
 * apply**. Neither is a per-device current-conditions API, and pointing an app at the web endpoints
 * anyway would be doing exactly what the Bureau asks people not to do.
 *
 * Open-Meteo meets every constraint the specification set: free, no API key, no registration, no
 * account, no subscription, global coverage including Australia, and a documented free tier of under
 * 10,000 calls a day. Its data is CC-BY 4.0, so the attribution below is an obligation, not a
 * courtesy, and Roadguard displays it on the About screen and in Diagnostics whenever weather is on.
 *
 * The free tier is **non-commercial**. Roadguard is a non-commercial open-source application, which
 * is why this qualifies; anyone repackaging it commercially needs to revisit that.
 *
 * ### Privacy
 *
 * Coordinates are rounded to [COORDINATE_PRECISION] decimal places -- about a kilometre -- before
 * they are sent. Weather does not vary within a kilometre, so nothing is lost, and the request
 * therefore cannot reveal which street the vehicle is on. No identifier of any kind is sent: no
 * account, no device id, no key. This is the only request Roadguard makes while driving, it happens
 * at most a few times an hour, and it happens only if the user turned weather on.
 */
class OpenMeteoWeatherSource(
    private val client: OkHttpClient = defaultClient(),
) : WeatherSource {

    override val name: String = "Open-Meteo"

    override val attribution: String = "Weather data by Open-Meteo.com, CC BY 4.0"

    override suspend fun fetch(latitude: Double, longitude: Double): Result<WeatherSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(endpointFor(latitude, longitude))
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body?.string() ?: throw IOException("empty response")
                    parse(body)
                }
            }.onFailure { Log.w(TAG, "weather fetch failed", it) }
        }

    /** Builds the request URL, rounding the position before it leaves the device. */
    fun endpointFor(latitude: Double, longitude: Double): String {
        val roundedLatitude = round(latitude)
        val roundedLongitude = round(longitude)
        return "$BASE_URL?latitude=$roundedLatitude&longitude=$roundedLongitude" +
            "&current=temperature_2m,precipitation,weather_code,wind_speed_10m" +
            "&wind_speed_unit=kmh&timezone=auto"
    }

    /**
     * Parses the `current` block.
     *
     * Every field is optional in the sense that Roadguard will not fail without it: a missing
     * temperature yields a snapshot with a null temperature, which the overlay simply omits, rather
     * than an error that turns weather off.
     */
    fun parse(json: String, nowEpochMs: Long = System.currentTimeMillis()): WeatherSnapshot {
        val current = JSONObject(json).optJSONObject("current")
            ?: throw IOException("response had no current conditions")
        val code = if (current.has("weather_code")) current.getInt("weather_code") else null
        return WeatherSnapshot(
            temperatureC = current.optDoubleOrNull("temperature_2m")?.toFloat(),
            conditionText = code?.let(::describeWmoCode),
            precipitationMmPerHour = current.optDoubleOrNull("precipitation")?.toFloat(),
            windSpeedKmh = current.optDoubleOrNull("wind_speed_10m")?.toFloat(),
            observedAtEpochMs = nowEpochMs,
            fetchedAtEpochMs = nowEpochMs,
            attribution = attribution,
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    private fun round(value: Double): Double {
        val factor = Math.pow(10.0, COORDINATE_PRECISION.toDouble())
        return (value * factor).roundToInt() / factor
    }

    companion object {
        private const val TAG = "RoadguardWeather"
        const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

        /**
         * Decimal places kept when sending a position.
         *
         * Two places is roughly 1.1 km at Australian latitudes. Weather is uniform at that scale, so
         * rounding costs nothing and means the request cannot identify a street.
         */
        const val COORDINATE_PRECISION = 2

        /** Open-Meteo asks that clients identify themselves; an anonymous client can be blocked. */
        const val USER_AGENT = "Roadguard/1.0 (offline dashcam; +https://github.com/tunlezah/Roadguard)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // A weather request must never be the reason anything waits.
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * WMO 4677 weather codes, as Open-Meteo documents them, in the short wording a driver can
         * read at a glance rather than the standard's full phrasing.
         */
        fun describeWmoCode(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51 -> "Light drizzle"
            53 -> "Drizzle"
            55 -> "Heavy drizzle"
            56, 57 -> "Freezing drizzle"
            61 -> "Light rain"
            63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71 -> "Light snow"
            73 -> "Snow"
            75 -> "Heavy snow"
            77 -> "Snow grains"
            80 -> "Light showers"
            81 -> "Showers"
            82 -> "Violent showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Unknown"
        }
    }
}
