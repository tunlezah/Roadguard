package io.github.tunlezah.roadguard.ui.firstrun

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.map.MapFailureReason
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.map.MapPackage
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors

/**
 * First-run setup.
 *
 * The point of this flow is that a user who finishes it has a working dashcam, not an app that needs
 * configuring. So it does the work rather than describing it: it asks for exactly the permissions
 * that are needed and says what breaks without each one, starts the GNSS receiver and shows it
 * acquiring, and installs the offline map itself.
 *
 * Two honesty rules run through it. The microphone is never bundled into the permission request,
 * because recording cabin conversation should be a decision, not a side effect of tapping "allow".
 * And when the map cannot be installed -- no network, or no published package -- the screen says so
 * plainly and lets the user continue, because recording has never depended on the map.
 */
@Composable
fun FirstRunScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FirstRunViewModel = viewModel(factory = FirstRunViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onPermissionResult() }

    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onMicrophoneRequested()
        viewModel.setMicrophoneEnabled(granted)
    }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
        ) {
            StepIndicator(current = state.step, modifier = Modifier.padding(vertical = 16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.step) {
                    SetupStep.Welcome -> WelcomeStep()
                    SetupStep.Permissions -> PermissionsStep(
                        state = state,
                        onRequest = { permissionLauncher.launch(viewModel.corePermissions) },
                        onRequestMicrophone = {
                            microphoneLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onOpenSystemSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", context.packageName, null)),
                                )
                            }
                        },
                    )

                    SetupStep.Location -> LocationStep(state)
                    SetupStep.Map -> MapStep(
                        state = state,
                        onRetry = viewModel::installMap,
                        onPause = viewModel::pauseMapInstall,
                        onSelectPackage = viewModel::selectMapPackage,
                    )

                    SetupStep.Ready -> ReadyStep(
                        state = state,
                        onAutoStartChange = viewModel::setAutoStart,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.step != SetupStep.Welcome) {
                    OutlinedButton(onClick = viewModel::back) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                when (state.step) {
                    SetupStep.Map -> if (!state.mapInstalled) {
                        TextButton(onClick = viewModel::skipMap) { Text("Skip for now") }
                    }

                    else -> Unit
                }
                Button(
                    onClick = {
                        if (state.step == SetupStep.Ready) {
                            viewModel.complete()
                            onFinished()
                        } else {
                            viewModel.next()
                        }
                    },
                    enabled = when (state.step) {
                        SetupStep.Permissions -> state.canLeavePermissions
                        else -> true
                    },
                ) {
                    Text(if (state.step == SetupStep.Ready) "Start recording" else "Continue")
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(current: SetupStep, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SetupStep.entries.forEach { step ->
            val active = step.ordinal <= current.ordinal
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(96.dp),
        )
        Text("Roadguard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Bullet("Records continuously in short clips and deletes the oldest when it runs out of room.")
        Bullet("Saves the footage around a bump automatically, and whenever you tap Protect.")
        Bullet("Works with no SIM, no mobile data and no account. Nothing is ever uploaded.")
        Text(
            "Set-up takes about a minute. Roadguard needs a couple of permissions and installs its " +
                "offline map once; after that it runs entirely on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionsStep(
    state: FirstRunUiState,
    onRequest: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Permissions", style = MaterialTheme.typography.headlineSmall)
        PermissionRow(
            iconRes = R.drawable.ic_videocam,
            title = "Camera",
            detail = "Without it Roadguard cannot record at all.",
            granted = state.cameraGranted,
        )
        PermissionRow(
            iconRes = R.drawable.ic_my_location,
            title = "Location",
            detail = "Without it there is no speed, no GPS stamp and the map cannot centre on you.",
            granted = state.locationGranted,
        )
        PermissionRow(
            iconRes = R.drawable.ic_notifications_active,
            title = "Notifications",
            detail = "Android will not let Roadguard keep recording in the background without one.",
            granted = state.notificationsGranted,
        )
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.cameraGranted) "Review permissions" else "Grant permissions")
        }
        if (!state.cameraGranted) {
            TextButton(onClick = onOpenSystemSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Already declined? Open Android settings")
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Microphone (optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Off by default, and asked for separately on purpose: with audio on, conversation inside " +
                "the car is recorded too. You can turn it on at any time in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRequestMicrophone, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.microphoneGranted) "Microphone allowed" else "Record audio too")
        }
    }
}

@Composable
private fun LocationStep(state: FirstRunUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Finding you", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.hasFix) {
                Icon(
                    painterResource(R.drawable.ic_gps_fixed),
                    contentDescription = null,
                    tint = LocalRoadguardStatusColors.current.ok,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            Column {
                Text(
                    text = if (state.hasFix) "Got a fix" else state.location.quality.label,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${state.location.satellitesUsed} of ${state.location.satellitesVisible} " +
                        "satellites in use" +
                        (state.location.accuracyMetres?.let { ", about %.0f m accuracy".format(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "GPS is a satellite receiver: it needs no SIM and no mobile data. Without a network the " +
                "first fix takes longer, because the phone cannot download the satellite almanac -- " +
                "outdoors with a clear view of the sky it is usually under a minute.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "You can carry on without a fix. Recording never waits for GPS.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MapStep(
    state: FirstRunUiState,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onSelectPackage: (MapPackage) -> Unit,
) {
    val busy = state.mapInstall is MapInstallState.Downloading ||
        state.mapInstall is MapInstallState.Verifying
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline map", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Roadguard installs the map itself, once. After that it works with no SIM, no mobile data " +
                "and no Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // The whole country is the default so nobody has to make a decision to get a working map,
        // and so crossing a state border never blanks it. A single state is the better choice for
        // most drivers, though, so the trade-off is stated rather than buried.
        if (state.mapPackages.size > 1) {
            MapRegionPicker(
                packages = state.mapPackages,
                selected = state.mapPackage,
                enabled = !busy && !state.mapInstalled,
                onSelect = onSelectPackage,
            )
        }

        state.mapPackage?.let { pack ->
            Text(
                text = pack.displayName + (
                    pack.sizeBytes?.let { " · ${it / (1024 * 1024)} MB download" }
                        ?: " · size not published"
                    ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${pack.attribution} · ${pack.licence}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val install = state.mapInstall) {
            is MapInstallState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                install.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = buildString {
                        append("${install.bytesDownloaded / (1024 * 1024)} MB downloaded")
                        install.etaSeconds?.let {
                            append(", about ${(it / 60).coerceAtLeast(1)} min remaining")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onPause) { Text("Pause") }
            }

            is MapInstallState.Paused -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Paused at ${install.bytesDownloaded / (1024 * 1024)} MB. It resumes from there.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onRetry) { Text("Resume") }
            }

            is MapInstallState.Verifying -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("Checking the download", style = MaterialTheme.typography.bodyMedium)
            }

            is MapInstallState.Installed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = LocalRoadguardStatusColors.current.ok,
                )
                Text(
                    "Installed (${install.sizeBytes / (1024 * 1024)} MB)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is MapInstallState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(install.reason.message, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when (install.reason) {
                        MapFailureReason.NoNetwork ->
                            "Connect to Wi-Fi once and Roadguard will install it. Everything else, " +
                                "including recording, works right now."

                        MapFailureReason.NotPublished ->
                            "The map file for this region is not available at the moment. Try a " +
                                "different region, or skip this step -- recording is unaffected " +
                                "and the map can be installed later from Storage."

                        MapFailureReason.NotConfigured ->
                            "This build has no map package configured. Recording is unaffected."

                        else -> install.detail ?: "You can try again later. Recording is unaffected."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (install.reason != MapFailureReason.NotConfigured &&
                    install.reason != MapFailureReason.NotPublished
                ) {
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }

            MapInstallState.NotInstalled -> Button(onClick = onRetry) { Text("Install the map") }
        }
    }
}

/**
 * Region chooser.
 *
 * Deliberately a plain list of radio rows rather than a dropdown: there are eight options, the
 * differences between them (size, and whether they carry street-level detail) matter, and a driver
 * setting the app up in a car park should not have to open a menu to see them.
 */
@Composable
private fun MapRegionPicker(
    packages: List<MapPackage>,
    selected: MapPackage?,
    enabled: Boolean,
    onSelect: (MapPackage) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        packages.forEach { pack ->
            val isSelected = pack.id == selected?.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(pack) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = buildString {
                            pack.sizeBytes?.let { append("${it / (1024 * 1024)} MB") }
                            append(
                                if (pack.isStreetLevel) {
                                    if (isEmpty()) "street level" else " · street level"
                                } else {
                                    if (isEmpty()) "main roads only" else " · main roads only"
                                },
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyStep(state: FirstRunUiState, onAutoStartChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ready", style = MaterialTheme.typography.headlineSmall)
        SummaryRow("Camera", if (state.cameraGranted) "allowed" else "not allowed", state.cameraGranted)
        SummaryRow("Location", if (state.locationGranted) "allowed" else "not allowed", state.locationGranted)
        SummaryRow(
            "Notifications",
            if (state.notificationsGranted) "allowed" else "not allowed",
            state.notificationsGranted,
        )
        SummaryRow("Microphone", if (state.microphoneGranted) "allowed" else "off", true)
        SummaryRow(
            "Offline map",
            when (state.mapInstall) {
                is MapInstallState.Installed -> "installed"
                is MapInstallState.Failed -> "not installed yet"
                else -> "not installed yet"
            },
            state.mapInstalled,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Start recording automatically", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "After a ${state.startupDelaySeconds} second delay when Roadguard opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.autoStartRecording, onCheckedChange = onAutoStartChange)
        }

        Text(
            "Roadguard is not a certified crash detector. It saves footage around what looks like an " +
                "impact, and it can miss one or react to a bad pothole. Tap Protect whenever " +
                "something matters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(iconRes: Int, title: String, detail: String, granted: Boolean) {
    val status = LocalRoadguardStatusColors.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = if (granted) status.ok else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (granted) "Allowed" else "Needed",
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) status.ok else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String, good: Boolean) {
    val status = LocalRoadguardStatusColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (good) status.ok else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
