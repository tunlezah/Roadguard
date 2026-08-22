package io.github.tunlezah.roadguard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "roadguard_settings")

/**
 * Persists [Settings] in a Preferences DataStore.
 *
 * Reading is a cold [Flow] so the whole app observes one source of truth, and every write
 * is validated by [validate] before it is stored -- a corrupt or out-of-range value can
 * never reach the recorder. Unknown enum names (for example after a downgrade) fall back
 * to the default rather than throwing, so a bad stored value cannot brick the app.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun update(transform: (Settings) -> Settings) {
        context.settingsDataStore.edit { preferences ->
            val updated = validate(transform(preferences.toSettings()))
            updated.writeTo(preferences)
        }
    }

    private object Keys {
        val QUALITY = stringPreferencesKey("quality")
        val FRAME_RATE = stringPreferencesKey("frame_rate")
        val CODEC = stringPreferencesKey("codec")
        val SEGMENT_LENGTH = stringPreferencesKey("segment_length")
        val CAMERA_FACING = stringPreferencesKey("camera_facing")
        val DUAL_CAMERA = booleanPreferencesKey("dual_camera")
        val STABILISATION = stringPreferencesKey("stabilisation")
        val NIGHT_ASSIST = stringPreferencesKey("night_assist")
        val RECORDING_ZOOM = floatPreferencesKey("recording_zoom")
        val MICROPHONE = booleanPreferencesKey("microphone")

        val PREVIEW_ZOOM = stringPreferencesKey("preview_zoom")
        val MAP_VISIBLE = booleanPreferencesKey("map_visible")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOUR = booleanPreferencesKey("dynamic_colour")
        val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SCREEN_OFF_DIMMING = booleanPreferencesKey("screen_off_dimming")

        val OVERLAY_DATE_TIME = booleanPreferencesKey("overlay_date_time")
        val OVERLAY_SPEED = booleanPreferencesKey("overlay_speed")
        val OVERLAY_COORDINATES = booleanPreferencesKey("overlay_coordinates")
        val OVERLAY_WEATHER = booleanPreferencesKey("overlay_weather")

        val AUTO_START = booleanPreferencesKey("auto_start")
        val STARTUP_DELAY = intPreferencesKey("startup_delay")

        val EVENT_DETECTION = booleanPreferencesKey("event_detection")
        val EVENT_SENSITIVITY = stringPreferencesKey("event_sensitivity")
        val PRE_EVENT = intPreferencesKey("pre_event_seconds")
        val POST_EVENT = intPreferencesKey("post_event_seconds")

        val LOOP_BUDGET = longPreferencesKey("loop_budget_bytes")
        val PROTECTED_WARNING = longPreferencesKey("protected_warning_bytes")
        val STORAGE_VOLUME = stringPreferencesKey("storage_volume_id")

        val LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val GPS_STORAGE = stringPreferencesKey("gps_storage")

        val ON_POWER_CONNECTED = stringPreferencesKey("on_power_connected")
        val ON_POWER_DISCONNECTED = stringPreferencesKey("on_power_disconnected")
        val POWER_STOP_DELAY = intPreferencesKey("power_stop_delay")
        val BATTERY_SAFE_THRESHOLD = intPreferencesKey("battery_safe_threshold")

        val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")

        val MAP_FOLLOWS = booleanPreferencesKey("map_follows_vehicle")
        val MAP_NORTH_UP = booleanPreferencesKey("map_north_up")
        val MAP_AUTO_DOWNLOAD = booleanPreferencesKey("map_auto_download")

        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val ACCEPTED_DISCLAIMER = booleanPreferencesKey("accepted_disclaimer")
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            quality = enumOr(this[Keys.QUALITY], defaults.quality),
            frameRate = enumOr(this[Keys.FRAME_RATE], defaults.frameRate),
            codec = enumOr(this[Keys.CODEC], defaults.codec),
            segmentLength = enumOr(this[Keys.SEGMENT_LENGTH], defaults.segmentLength),
            cameraFacing = enumOr(this[Keys.CAMERA_FACING], defaults.cameraFacing),
            dualCameraEnabled = this[Keys.DUAL_CAMERA] ?: defaults.dualCameraEnabled,
            videoStabilisation = enumOr(this[Keys.STABILISATION], defaults.videoStabilisation),
            nightAssist = enumOr(this[Keys.NIGHT_ASSIST], defaults.nightAssist),
            recordingZoom = this[Keys.RECORDING_ZOOM] ?: defaults.recordingZoom,
            microphoneEnabled = this[Keys.MICROPHONE] ?: defaults.microphoneEnabled,
            previewZoom = enumOr(this[Keys.PREVIEW_ZOOM], defaults.previewZoom),
            mapVisible = this[Keys.MAP_VISIBLE] ?: defaults.mapVisible,
            theme = enumOr(this[Keys.THEME], defaults.theme),
            useDynamicColour = this[Keys.DYNAMIC_COLOUR] ?: defaults.useDynamicColour,
            orientationMode = enumOr(this[Keys.ORIENTATION_MODE], defaults.orientationMode),
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            screenOffDimming = this[Keys.SCREEN_OFF_DIMMING] ?: defaults.screenOffDimming,
            overlayDateTime = this[Keys.OVERLAY_DATE_TIME] ?: defaults.overlayDateTime,
            overlaySpeed = this[Keys.OVERLAY_SPEED] ?: defaults.overlaySpeed,
            overlayCoordinates = this[Keys.OVERLAY_COORDINATES] ?: defaults.overlayCoordinates,
            overlayWeather = this[Keys.OVERLAY_WEATHER] ?: defaults.overlayWeather,
            autoStartRecording = this[Keys.AUTO_START] ?: defaults.autoStartRecording,
            startupDelaySeconds = this[Keys.STARTUP_DELAY] ?: defaults.startupDelaySeconds,
            eventDetectionEnabled = this[Keys.EVENT_DETECTION] ?: defaults.eventDetectionEnabled,
            eventSensitivity = enumOr(this[Keys.EVENT_SENSITIVITY], defaults.eventSensitivity),
            preEventSeconds = this[Keys.PRE_EVENT] ?: defaults.preEventSeconds,
            postEventSeconds = this[Keys.POST_EVENT] ?: defaults.postEventSeconds,
            loopBudgetBytes = this[Keys.LOOP_BUDGET] ?: defaults.loopBudgetBytes,
            protectedWarningBytes = this[Keys.PROTECTED_WARNING] ?: defaults.protectedWarningBytes,
            storageVolumeId = this[Keys.STORAGE_VOLUME] ?: defaults.storageVolumeId,
            locationEnabled = this[Keys.LOCATION_ENABLED] ?: defaults.locationEnabled,
            speedUnit = enumOr(this[Keys.SPEED_UNIT], defaults.speedUnit),
            gpsStorage = enumOr(this[Keys.GPS_STORAGE], defaults.gpsStorage),
            onPowerConnected = enumOr(this[Keys.ON_POWER_CONNECTED], defaults.onPowerConnected),
            onPowerDisconnected = enumOr(this[Keys.ON_POWER_DISCONNECTED], defaults.onPowerDisconnected),
            powerDisconnectStopDelaySeconds = this[Keys.POWER_STOP_DELAY] ?: defaults.powerDisconnectStopDelaySeconds,
            batterySafeThresholdPercent = this[Keys.BATTERY_SAFE_THRESHOLD] ?: defaults.batterySafeThresholdPercent,
            weatherEnabled = this[Keys.WEATHER_ENABLED] ?: defaults.weatherEnabled,
            mapFollowsVehicle = this[Keys.MAP_FOLLOWS] ?: defaults.mapFollowsVehicle,
            mapNorthUp = this[Keys.MAP_NORTH_UP] ?: defaults.mapNorthUp,
            mapAutoDownload = this[Keys.MAP_AUTO_DOWNLOAD] ?: defaults.mapAutoDownload,
            setupComplete = this[Keys.SETUP_COMPLETE] ?: defaults.setupComplete,
            acceptedRecordingDisclaimer = this[Keys.ACCEPTED_DISCLAIMER] ?: defaults.acceptedRecordingDisclaimer,
        )
    }

    private fun Settings.writeTo(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences[Keys.QUALITY] = quality.name
        preferences[Keys.FRAME_RATE] = frameRate.name
        preferences[Keys.CODEC] = codec.name
        preferences[Keys.SEGMENT_LENGTH] = segmentLength.name
        preferences[Keys.CAMERA_FACING] = cameraFacing.name
        preferences[Keys.DUAL_CAMERA] = dualCameraEnabled
        preferences[Keys.STABILISATION] = videoStabilisation.name
        preferences[Keys.NIGHT_ASSIST] = nightAssist.name
        preferences[Keys.RECORDING_ZOOM] = recordingZoom
        preferences[Keys.MICROPHONE] = microphoneEnabled
        preferences[Keys.PREVIEW_ZOOM] = previewZoom.name
        preferences[Keys.MAP_VISIBLE] = mapVisible
        preferences[Keys.THEME] = theme.name
        preferences[Keys.DYNAMIC_COLOUR] = useDynamicColour
        preferences[Keys.ORIENTATION_MODE] = orientationMode.name
        preferences[Keys.KEEP_SCREEN_ON] = keepScreenOn
        preferences[Keys.SCREEN_OFF_DIMMING] = screenOffDimming
        preferences[Keys.OVERLAY_DATE_TIME] = overlayDateTime
        preferences[Keys.OVERLAY_SPEED] = overlaySpeed
        preferences[Keys.OVERLAY_COORDINATES] = overlayCoordinates
        preferences[Keys.OVERLAY_WEATHER] = overlayWeather
        preferences[Keys.AUTO_START] = autoStartRecording
        preferences[Keys.STARTUP_DELAY] = startupDelaySeconds
        preferences[Keys.EVENT_DETECTION] = eventDetectionEnabled
        preferences[Keys.EVENT_SENSITIVITY] = eventSensitivity.name
        preferences[Keys.PRE_EVENT] = preEventSeconds
        preferences[Keys.POST_EVENT] = postEventSeconds
        preferences[Keys.LOOP_BUDGET] = loopBudgetBytes
        preferences[Keys.PROTECTED_WARNING] = protectedWarningBytes
        storageVolumeId?.let { preferences[Keys.STORAGE_VOLUME] = it } ?: preferences.remove(Keys.STORAGE_VOLUME)
        preferences[Keys.LOCATION_ENABLED] = locationEnabled
        preferences[Keys.SPEED_UNIT] = speedUnit.name
        preferences[Keys.GPS_STORAGE] = gpsStorage.name
        preferences[Keys.ON_POWER_CONNECTED] = onPowerConnected.name
        preferences[Keys.ON_POWER_DISCONNECTED] = onPowerDisconnected.name
        preferences[Keys.POWER_STOP_DELAY] = powerDisconnectStopDelaySeconds
        preferences[Keys.BATTERY_SAFE_THRESHOLD] = batterySafeThresholdPercent
        preferences[Keys.WEATHER_ENABLED] = weatherEnabled
        preferences[Keys.MAP_FOLLOWS] = mapFollowsVehicle
        preferences[Keys.MAP_NORTH_UP] = mapNorthUp
        preferences[Keys.MAP_AUTO_DOWNLOAD] = mapAutoDownload
        preferences[Keys.SETUP_COMPLETE] = setupComplete
        preferences[Keys.ACCEPTED_DISCLAIMER] = acceptedRecordingDisclaimer
    }

    companion object {
        /**
         * Clamps every numeric setting into a range the rest of the app can rely on.
         *
         * Kept as a pure function on the companion so it can be unit tested without a
         * DataStore, and so that the recorder never has to defend itself against, say, a
         * negative segment budget.
         */
        fun validate(settings: Settings): Settings = settings.copy(
            recordingZoom = settings.recordingZoom.coerceIn(1.0f, 8.0f),
            startupDelaySeconds = settings.startupDelaySeconds.coerceIn(
                STARTUP_DELAY_RANGE.first,
                STARTUP_DELAY_RANGE.last,
            ),
            preEventSeconds = settings.preEventSeconds.coerceIn(0, 120),
            postEventSeconds = settings.postEventSeconds.coerceIn(0, 300),
            loopBudgetBytes = settings.loopBudgetBytes.coerceAtLeast(LoopBudget.MIN_BYTES),
            protectedWarningBytes = settings.protectedWarningBytes.coerceAtLeast(0L),
            powerDisconnectStopDelaySeconds = settings.powerDisconnectStopDelaySeconds.coerceIn(0, 3600),
            batterySafeThresholdPercent = settings.batterySafeThresholdPercent.coerceIn(0, 100),
        )
    }
}

private inline fun <reified T : Enum<T>> enumOr(stored: String?, fallback: T): T =
    stored?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback
