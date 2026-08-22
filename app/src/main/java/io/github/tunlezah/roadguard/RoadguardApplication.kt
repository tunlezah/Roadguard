package io.github.tunlezah.roadguard

import android.app.Application
import io.github.tunlezah.roadguard.core.RoadguardContainer

/**
 * Roadguard's application object.
 *
 * Holds the one dependency container and kicks off the start-up work that must happen before the
 * recorder can safely write: reconciling the recording index against the filesystem after whatever
 * happened last time the process ended.
 *
 * Deliberately does *not* start the camera, request permissions or touch the network. A dashcam's
 * application object should be boring: everything expensive is lazy, so opening the app just to look
 * at the map does not spin up the camera probe.
 */
class RoadguardApplication : Application() {

    val container: RoadguardContainer by lazy { RoadguardContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.onApplicationCreate()
    }
}
