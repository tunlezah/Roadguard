package io.github.tunlezah.roadguard.ui.main

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.map.MapFailureReason
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.map.MapStyleProvider
import io.github.tunlezah.roadguard.map.MapStyleSpec
import io.github.tunlezah.roadguard.map.MapWorkBudget
import io.github.tunlezah.roadguard.ui.theme.PaneCorner
import io.github.tunlezah.roadguard.ui.theme.ThemeMode
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.renderer.MapRenderer
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/**
 * The map pane.
 *
 * ### Independent of recording, by construction
 *
 * The map has no reference to the recorder. It renders from an installed offline archive and a
 * location fix, both of which exist whether or not anything is being recorded, which is exactly what
 * the specification requires: a user can open Roadguard purely to use the map.
 *
 * ### Subordinate to recording, also by construction
 *
 * The pane obeys a [MapWorkBudget] set by the thermal engine. Under pressure it lowers the
 * renderer's frame cap, then stops requesting new frames, then tears the view down entirely -- and
 * it can do all of that without the recorder noticing, because it owns no camera or encoder
 * resources. `MapView.setMaximumFps` and the renderer's refresh mode are the two levers that make
 * throttling cheap rather than an all-or-nothing switch.
 *
 * ### Offline
 *
 * No network is involved once the archive is installed: the style points at a local `pmtiles://` or
 * `mbtiles://` file and, when the package ships them, local glyphs. If MapLibre reports a
 * style-load failure the pane tries the next candidate URL form (see [MapStyleProvider]) rather than
 * showing a blank map with no explanation.
 */
@Composable
fun MapPane(
    state: MainUiState,
    onRecentre: () -> Unit,
    onRetryInstall: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    workBudget: MapWorkBudget = MapWorkBudget(),
    themeMode: ThemeMode = ThemeMode.Dark,
) {
    Box(
        modifier = modifier
            .clip(PaneCorner)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        when (val install = state.mapInstall) {
            is MapInstallState.Installed ->
                if (workBudget.renderEnabled) {
                    MapSurface(
                        state = state,
                        workBudget = workBudget,
                        themeMode = themeMode,
                        modifier = Modifier.fillMaxSize(),
                    )
                    MapOverlayControls(
                        state = state,
                        onRecentre = onRecentre,
                        onHide = onHide,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MapPausedForHeat(Modifier.fillMaxSize())
                }

            is MapInstallState.Downloading -> MapInstallProgress(
                title = "Installing the offline map",
                detail = install.totalBytes
                    ?.let { total -> "${install.bytesDownloaded / (1024 * 1024)} MB of ${total / (1024 * 1024)} MB" }
                    ?: "${install.bytesDownloaded / (1024 * 1024)} MB downloaded",
                eta = install.etaSeconds?.let { "about ${(it / 60).coerceAtLeast(1)} min remaining" },
                fraction = install.fraction,
                modifier = Modifier.fillMaxSize(),
            )

            is MapInstallState.Verifying -> MapInstallProgress(
                title = "Checking the map data",
                detail = "Making sure the download is complete",
                eta = null,
                fraction = null,
                modifier = Modifier.fillMaxSize(),
            )

            is MapInstallState.Paused -> MapMessage(
                iconRes = R.drawable.ic_pause,
                title = "Map installation paused",
                body = "${install.bytesDownloaded / (1024 * 1024)} MB downloaded so far. It will resume where it left off.",
                actionLabel = "Resume",
                onAction = onRetryInstall,
                modifier = Modifier.fillMaxSize(),
            )

            is MapInstallState.Failed -> MapMessage(
                iconRes = when (install.reason) {
                    MapFailureReason.NoNetwork -> R.drawable.ic_cloud_off
                    MapFailureReason.InsufficientStorage -> R.drawable.ic_storage
                    MapFailureReason.NotPublished, MapFailureReason.NotConfigured ->
                        R.drawable.ic_help_outline

                    else -> R.drawable.ic_error_outline
                },
                title = install.reason.message,
                body = when (install.reason) {
                    MapFailureReason.NoNetwork ->
                        "Recording works right now without it. Connect to Wi-Fi once and Roadguard will " +
                            "install the map; after that the map works with no SIM and no data at all."

                    MapFailureReason.NotConfigured ->
                        "This build has no offline map package configured, so the map cannot be installed."

                    MapFailureReason.NotPublished ->
                        "Roadguard builds its own map data rather than downloading it from someone " +
                            "else's server. Run the \"Build offline map\" workflow in this project " +
                            "once and the map will install itself here. Recording is unaffected."

                    else -> install.detail ?: "You can try again at any time. Recording is unaffected."
                },
                actionLabel = when (install.reason) {
                    MapFailureReason.NotConfigured, MapFailureReason.NotPublished -> null
                    else -> "Try again"
                },
                onAction = onRetryInstall,
                modifier = Modifier.fillMaxSize(),
            )

            MapInstallState.NotInstalled -> MapMessage(
                iconRes = R.drawable.ic_download_for_offline,
                title = "Offline map not installed yet",
                body = "Roadguard installs the map for you once, then it works with no SIM, no mobile data and " +
                    "no Wi-Fi.",
                actionLabel = "Install now",
                onAction = onRetryInstall,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The MapLibre view itself.
 *
 * `MapView` is a `View` with its own lifecycle contract, so it is driven from the composition's
 * lifecycle owner and destroyed in `onDispose`. Getting this wrong leaks a GL context, which on the
 * baseline device shows up as the map slowing down over a long drive.
 */
@Composable
private fun MapSurface(
    state: MainUiState,
    workBudget: MapWorkBudget,
    themeMode: ThemeMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val styleProvider = remember(context) { MapStyleProvider(context) }
    var mapReference by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleError by remember { mutableStateOf<String?>(null) }

    // MapLibre.getInstance must run before any MapView is created. No API key is set and none is
    // needed: Roadguard reads only local files and never contacts a tile server.
    remember(context) {
        MapLibre.getInstance(context)
        // Belt and braces on storage: a fully local style should never write to the ambient cache,
        // and a zero-byte budget guarantees a 900 MB archive is not shadowed by a second copy.
        runCatching {
            OfflineManager.getInstance(context).setMaximumAmbientCacheSize(0L, null)
        }
    }

    val installed = state.mapInstall as? MapInstallState.Installed
    val spec: MapStyleSpec? = remember(installed?.packageId, themeMode) {
        val container = io.github.tunlezah.roadguard.core.RoadguardContainer.from(context)
        val pack = container.mapRepository.selectedPackage ?: return@remember null
        styleProvider.styleFor(container.mapRepository.directoryFor(pack), pack, themeMode)
    }

    if (spec == null) {
        MapMessage(
            iconRes = R.drawable.ic_error_outline,
            title = "Map data could not be read",
            body = "The installed package does not contain a tile archive Roadguard can open. " +
                "Reinstalling the map from Settings should fix it.",
            actionLabel = null,
            onAction = {},
            modifier = modifier,
        )
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            MapView(viewContext).apply {
                onCreate(null)
                addOnDidFailLoadingMapListener { reason ->
                    Log.w(TAG, "map failed to load: $reason")
                    styleError = reason
                }
                getMapAsync { map ->
                    mapReference = map
                    map.uiSettings.apply {
                        isRotateGesturesEnabled = false
                        isTiltGesturesEnabled = false
                        isAttributionEnabled = true
                        isLogoEnabled = false
                        isCompassEnabled = false
                    }
                    map.setMinZoomPreference(MIN_ZOOM)
                    // The archive stops at zoom 14; MapLibre overzooms that geometry up to 18,
                    // which is the range a driver actually reads.
                    map.setMaxZoomPreference(MAX_ZOOM)
                    // Prefetching coarse tiles is a network latency optimisation. Against a local
                    // archive it buys nothing and costs decode work, so it stays off.
                    map.prefetchesTiles = false
                    map.prefetchZoomDelta = 0
                    map.setStyle(Style.Builder().fromJson(spec.json))
                }
            }
        },
        update = { view ->
            view.setMaximumFps(if (workBudget.allowAnimation) MAX_FPS else THROTTLED_FPS)
            view.setRenderingRefreshMode(
                if (workBudget.renderEnabled) {
                    MapRenderer.RenderingRefreshMode.WHEN_DIRTY
                } else {
                    MapRenderer.RenderingRefreshMode.WHEN_DIRTY
                },
            )
        },
        onRelease = { view ->
            runCatching { view.onStop() }
            runCatching { view.onDestroy() }
        },
    )

    // Follow the vehicle. Position updates are throttled by the work budget rather than by a fixed
    // interval, so heat translates directly into fewer camera animations.
    LaunchedEffect(state.location.latitude, state.location.longitude, state.settings.mapFollowsVehicle) {
        val map = mapReference ?: return@LaunchedEffect
        val latitude = state.location.latitude ?: return@LaunchedEffect
        val longitude = state.location.longitude ?: return@LaunchedEffect
        if (!state.settings.mapFollowsVehicle) return@LaunchedEffect
        val bearing = if (state.settings.mapNorthUp) 0.0 else (state.location.bearingDegrees ?: 0f).toDouble()
        val position = CameraPosition.Builder()
            .target(LatLng(latitude, longitude))
            .zoom(FOLLOW_ZOOM)
            .bearing(bearing)
            .build()
        if (workBudget.allowAnimation) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(position), CAMERA_ANIMATION_MS)
        } else {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
        }
    }

    // Keep the vehicle marker in step with the location engine. The marker is a GeoJSON source
    // Roadguard owns, rather than MapLibre's LocationComponent, so the map never touches the
    // location permission or the GNSS receiver and cannot compete with the recorder for either.
    LaunchedEffect(state.location.latitude, state.location.longitude) {
        val map = mapReference ?: return@LaunchedEffect
        val latitude = state.location.latitude ?: return@LaunchedEffect
        val longitude = state.location.longitude ?: return@LaunchedEffect
        map.style?.let { style ->
            (style.getSource(MapStyleProvider.VEHICLE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
                Point.fromLngLat(longitude, latitude),
            )
        }
    }

    styleError?.let { reason ->
        Log.w(TAG, "map style reported: $reason")
    }
}

@Composable
private fun MapOverlayControls(
    state: MainUiState,
    onRecentre: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.padding(10.dp)) {
        Column(
            modifier = Modifier.align(Alignment.TopEnd),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FilledIconButton(
                onClick = onRecentre,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.settings.mapFollowsVehicle) R.drawable.ic_my_location else R.drawable.ic_location_searching,
                    ),
                    contentDescription = "Centre the map on the vehicle",
                    modifier = Modifier.size(20.dp),
                )
            }
            FilledIconButton(
                onClick = onHide,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fullscreen),
                    contentDescription = "Hide the map and give the camera the whole screen",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun MapPausedForHeat(modifier: Modifier = Modifier) {
    val status = LocalRoadguardStatusColors.current
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_thermostat),
            contentDescription = null,
            tint = status.warning,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = "Map paused to keep recording",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "The device is warm, so map rendering has been stopped. Recording continues.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MapInstallProgress(
    title: String,
    detail: String,
    eta: String?,
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        eta?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Recording is not waiting for this.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MapMessage(
    iconRes: Int,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private const val TAG = "RoadguardMapPane"
private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 18.0
private const val FOLLOW_ZOOM = 15.5
private const val CAMERA_ANIMATION_MS = 900
private const val MAX_FPS = 30
private const val THROTTLED_FPS = 5
