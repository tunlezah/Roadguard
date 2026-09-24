package io.github.tunlezah.roadguard.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.camera.CameraOrientationTracker
import io.github.tunlezah.roadguard.camera.CameraSession
import io.github.tunlezah.roadguard.capability.DeviceCapabilityProbe
import io.github.tunlezah.roadguard.data.RoadguardDatabase
import io.github.tunlezah.roadguard.event.EventSensorSource
import io.github.tunlezah.roadguard.event.ProtectionCoordinator
import io.github.tunlezah.roadguard.location.LocationEngine
import io.github.tunlezah.roadguard.map.PlaceLookup
import io.github.tunlezah.roadguard.overlay.OverlayComposer
import io.github.tunlezah.roadguard.power.PowerMonitor
import io.github.tunlezah.roadguard.settings.SettingsRepository
import io.github.tunlezah.roadguard.storage.StorageManager
import io.github.tunlezah.roadguard.thermal.AndroidThermalSource
import io.github.tunlezah.roadguard.trip.PlaceNames
import io.github.tunlezah.roadguard.trip.TripRepository
import io.github.tunlezah.roadguard.weather.WeatherState
import io.github.tunlezah.roadguard.weather.WeatherUnavailableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * Guards the one thing a driver must be able to trust about the Stop button: that pressing it
 * actually leaves the recorder *stopped*.
 *
 * [RecorderStatus.Stopping] is a transient state, shown only while the closing segment is being
 * finalised. A regression once passed it as the *terminal* status of a user-initiated stop, so
 * after a normal stop the recorder sat in "Stopping" forever -- the status chip and the ongoing
 * notification never cleared, which is exactly the "it says stopping but never changes" report.
 * Nothing else moves the recorder out of Stopping, so only the stop path itself can get this right.
 *
 * The controller is hard to exercise (it owns a camera), but the stop path is pure teardown and
 * every collaborator it touches is null-guarded, so it runs cleanly with no recording in flight.
 * The test dispatcher stands in for the main dispatcher so the two `withContext(Main)` hops in the
 * teardown are advanced deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingControllerStopTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a user-initiated stop leaves the recorder idle, not parked in stopping`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = newController(scope = this, dispatcher = StandardTestDispatcher(testScheduler))

        controller.stop()
        advanceUntilIdle()

        assertThat(controller.state.value.status).isEqualTo(RecorderStatus.Idle)
    }

    @Test
    fun `stopping twice is harmless`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = newController(scope = this, dispatcher = StandardTestDispatcher(testScheduler))

        controller.stop()
        controller.stop()
        advanceUntilIdle()

        assertThat(controller.state.value.status).isEqualTo(RecorderStatus.Idle)
    }

    @Test
    fun `a stop overtakes a start that was requested just before it`() = runTest {
        // The session token is read when start() is called, so a Stop pressed straight after
        // wins even though the start has not run yet. Without that, the start could run after
        // the stop and leave a recording going that the driver had just stopped.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = newController(scope = this, dispatcher = StandardTestDispatcher(testScheduler))

        controller.start()
        controller.stop()
        advanceUntilIdle()

        // Had the start run, it would have failed for want of the recording service and said so.
        assertThat(controller.state.value.status).isEqualTo(RecorderStatus.Idle)
        assertThat(controller.state.value.lastErrorMessage).isNull()
    }

    @Test
    fun `a start without the recording service says so rather than sitting in starting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = newController(scope = this, dispatcher = StandardTestDispatcher(testScheduler))

        controller.start()
        advanceUntilIdle()

        assertThat(controller.state.value.status).isEqualTo(RecorderStatus.Failed)
        assertThat(controller.state.value.lastErrorMessage).isEqualTo("Recording service is not running")
    }

    @Test
    fun `a shutdown with nothing recording returns at once and changes nothing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = newController(scope = this, dispatcher = StandardTestDispatcher(testScheduler))

        controller.finalizeForShutdown(timeoutMs = 6_000)

        assertThat(controller.state.value.status).isEqualTo(RecorderStatus.Idle)
        assertThat(controller.state.value.lastErrorMessage).isNull()
        assertThat(currentTime).isEqualTo(0L)
    }

    private fun newController(
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): RecordingController {
        val database = RoadguardDatabase.createInMemory(context)
        val storage = StorageManager(context, database.segments(), database.trips())
        val cameraSession = CameraSession(context)
        val sensorSource = EventSensorSource(context)
        val places = object : PlaceLookup {
            override val isAvailable: Boolean = false
            override suspend fun resolve(latitude: Double, longitude: Double): PlaceNames? = null
        }
        return RecordingController(
            context = context,
            scope = scope,
            settingsRepository = SettingsRepository(context),
            cameraSession = cameraSession,
            capabilityProbe = DeviceCapabilityProbe(context, cameraSession, sensorSource),
            storage = storage,
            segments = database.segments(),
            protection = ProtectionCoordinator(database.segments(), database.events(), storage),
            locationEngine = LocationEngine(context, Executors.newSingleThreadExecutor()),
            trips = TripRepository(database.trips(), database.segments(), storage, places),
            trackRecorder = io.github.tunlezah.roadguard.location.TrackRecorder(),
            sensorSource = sensorSource,
            thermalSource = AndroidThermalSource(context, scope),
            powerMonitor = PowerMonitor(context),
            orientationTracker = CameraOrientationTracker(context),
            overlayComposer = OverlayComposer(),
            weatherState = MutableStateFlow<WeatherState>(
                WeatherState.Unavailable(WeatherUnavailableReason.Disabled),
            ),
            dispatcher = dispatcher,
        )
    }
}
