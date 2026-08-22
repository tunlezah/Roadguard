package io.github.tunlezah.roadguard.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tunlezah.roadguard.BuildConfig
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.core.RoadguardContainer

/**
 * About, privacy and limitations.
 *
 * The limitations section is the point of this screen. Roadguard makes claims a user might rely on
 * in an unpleasant situation -- that it saved the footage, that it kept recording, that the speed
 * stamp is right -- so the places where those claims are weaker deserve to be written down where
 * somebody can read them, not buried in a repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = RoadguardContainer.from(context)
    val mapAttribution = container.mapRepository.selectedPackage
    val weatherAttribution = container.weatherRepository.attribution

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("About Roadguard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp),
                )
                Text("Roadguard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "A dashcam that treats a phone as its hardware: continuous segmented recording, " +
                    "automatic protection of the footage around an impact, a speed and time stamp " +
                    "burned into the video, an offline map, and a thermal policy that would rather " +
                    "drop quality than stop recording.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Heading("Privacy")
            Body(
                "Everything stays on this device. Roadguard has no account, no cloud storage, no " +
                    "analytics and no crash reporting. Video, audio, GPS tracks and diagnostics are " +
                    "written to the app's own storage and are only shared if you share them yourself.",
            )
            Body(
                "Android's cloud backup and device-to-device transfer are switched off for this app, " +
                    "so the platform cannot copy your recordings or settings off the phone either.",
            )
            Body(
                "The network is used for exactly two things, both optional: downloading the offline " +
                    "map once, and -- only if you turn it on -- fetching weather for your approximate " +
                    "position. Recording never waits for either.",
            )
            Body(
                "The microphone is off by default and permission for it is only requested if you turn " +
                    "audio recording on. Note that recording conversations may be regulated where you " +
                    "live.",
            )

            Heading("Limitations")
            Body(
                "Roadguard is not a certified crash detector. It watches the accelerometer for an " +
                    "impact signature and can miss a real collision or react to a bad pothole. Tap " +
                    "Protect whenever something matters.",
            )
            Body(
                "Recording profiles are chosen from what this device reports at runtime, not from a " +
                    "list of tested phones. They are a conservative starting point, not a validated " +
                    "measurement of your hardware -- Diagnostics shows exactly what was probed.",
            )
            Body(
                "Recording with the screen off relies on Android's foreground-service rules, which " +
                    "some manufacturers layer their own battery management on top of. If recording " +
                    "stops when the screen goes off, exempting Roadguard from battery optimisation in " +
                    "Android's settings usually fixes it.",
            )
            Body(
                "Speed comes from GNSS, so it is unavailable in tunnels and imprecise until the fix " +
                    "settles. Roadguard shows \"--\" rather than a number it does not trust.",
            )
            Body(
                "Uninstalling the app deletes its recordings, because they live in the app's own " +
                    "storage. Export anything you need to keep first.",
            )

            Heading("Attribution")
            mapAttribution?.let { pack ->
                Body("Map data: ${pack.attribution}, licensed under the ${pack.licence}.")
            } ?: Body("Map data: © OpenStreetMap contributors, licensed under the ODbL 1.0.")
            Body("Map rendering: MapLibre Native, BSD-2-Clause. Vector tile schema: Shortbread 1.0, CC0.")
            Body("Map label fonts: Noto Sans under the SIL Open Font License 1.1. Map icons: CC0.")
            Body("Interface icons: Material Icons by Google, Apache License 2.0.")
            Body("Camera, media and UI: AndroidX and Jetpack Compose, Apache License 2.0.")
            if (weatherAttribution.isNotBlank()) Body("Weather: $weatherAttribution")

            androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
