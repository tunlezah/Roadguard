package io.github.tunlezah.roadguard.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.github.tunlezah.roadguard.storage.StorageBudget
import io.github.tunlezah.roadguard.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs and owns the offline map data.
 *
 * ### The product rule this implements
 *
 * The user must never be asked to find, download or copy map files. On first run Roadguard installs
 * the map itself, shows progress, survives interruption, and afterwards works with no SIM, no
 * mobile data and no Wi-Fi. If the first run happens with no connection, the app says so plainly,
 * carries on working -- including recording, which never waits for the map -- and retries later.
 *
 * ### Why installation cannot corrupt anything
 *
 * The download only ever writes a `.part` file. It is verified before it is installed, and the
 * install itself is a rename into place followed by a marker file written last. A crash at any point
 * leaves either the previous good install or a partial download that the next attempt resumes, never
 * a half-installed map that the renderer would fail on.
 *
 * ### Why it cannot hurt recording
 *
 * The repository runs on the application scope, checks the storage budget before it starts (using
 * the same reserve the recorder respects, so a map download can never eat the recording headroom),
 * and exposes a [MapWorkBudget] the thermal engine can throttle to nothing.
 */
class MapRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val storage: StorageManager,
    private val downloader: MapDownloader = MapDownloader(),
) {
    private val _installState = MutableStateFlow<MapInstallState>(MapInstallState.NotInstalled)
    val installState: StateFlow<MapInstallState> = _installState.asStateFlow()

    private val _workBudget = MutableStateFlow(MapWorkBudget())
    val workBudget: StateFlow<MapWorkBudget> = _workBudget.asStateFlow()

    private var installJob: Job? = null

    val packages: List<MapPackage> by lazy { MapPackageCatalog.load(context) }

    var selectedPackage: MapPackage? = null
        private set

    /**
     * Chooses which region to install.
     *
     * Only meaningful before or between installs: switching region while a download is running
     * would leave a half-downloaded file for a package nobody selected, so an active download is
     * cancelled first. Any *installed* archive for the previous region is left alone — the caller
     * decides whether to remove it, because on a phone the user may well want to keep both.
     *
     * @return true when the selection changed.
     */
    fun select(pack: MapPackage): Boolean {
        if (selectedPackage?.id == pack.id) return false
        installJob?.cancel()
        installJob = null
        selectedPackage = pack
        // Off the caller's thread, like refresh(): reading the install marker is file I/O, and
        // this is called from a settings tap.
        scope.launch { _installState.value = readInstalledState(pack) ?: MapInstallState.NotInstalled }
        return true
    }

    /**
     * Refreshes state from disk. Cheap; safe to call on every app start.
     *
     * @param preferredId the region the user chose on a previous run. Falls back to the catalogue
     *   default when it is null or names a package this build no longer ships.
     */
    fun refresh(preferredId: String? = null) {
        scope.launch {
            selectedPackage = selectedPackage
                ?: MapPackageCatalog.byId(packages, preferredId)
                ?: MapPackageCatalog.defaultFor(packages)
            val chosen = selectedPackage
            if (chosen == null) {
                _installState.value = MapInstallState.Failed(
                    packageId = "",
                    reason = MapFailureReason.NotConfigured,
                    detail = null,
                )
                return@launch
            }
            _installState.value = readInstalledState(chosen) ?: MapInstallState.NotInstalled
        }
    }

    /** True when a usable offline map is present. The map pane renders only when this holds. */
    fun isInstalled(): Boolean = _installState.value is MapInstallState.Installed

    fun setWorkBudget(budget: MapWorkBudget) {
        _workBudget.value = budget
    }

    /**
     * Starts (or resumes) installation.
     *
     * Idempotent: calling it while a download is in flight does nothing, so the first-run screen,
     * the settings screen and a retry button can all call it freely.
     */
    fun install() {
        if (installJob?.isActive == true) return
        val chosen = selectedPackage ?: MapPackageCatalog.defaultFor(packages)?.also { selectedPackage = it }
        if (chosen == null) {
            _installState.value = MapInstallState.Failed("", MapFailureReason.NotConfigured, null)
            return
        }
        installJob = scope.launch { runInstall(chosen) }
    }

    /** Pauses the download, keeping what has been fetched so far for a later resume. */
    fun pause() {
        val current = _installState.value
        installJob?.cancel()
        installJob = null
        if (current is MapInstallState.Downloading) {
            _installState.value = MapInstallState.Paused(
                packageId = current.packageId,
                bytesDownloaded = current.bytesDownloaded,
                totalBytes = current.totalBytes,
            )
        }
    }

    /** Removes the installed map and any partial download, freeing its storage. */
    suspend fun uninstall() = withContext(Dispatchers.IO) {
        installJob?.cancel()
        val chosen = selectedPackage ?: return@withContext
        val directory = directoryFor(chosen)
        directory.deleteRecursively()
        downloader.discardPartial(archiveFor(chosen))
        archiveFor(chosen).delete()
        _installState.value = MapInstallState.NotInstalled
    }

    private suspend fun runInstall(chosen: MapPackage) {
        if (!hasNetwork()) {
            _installState.value = MapInstallState.Failed(chosen.id, MapFailureReason.NoNetwork, null)
            return
        }

        // Never let a map download eat the recorder's headroom: require the published size plus the
        // same reserve the loop budget keeps, and refuse politely rather than filling the volume.
        val free = storage.freeBytes()
        val needed = (chosen.sizeBytes ?: 0L) * SPACE_SAFETY_MULTIPLIER
        val reserve = StorageBudget.MIN_RESERVE_BYTES
        if (chosen.sizeBytes != null && free < needed + reserve) {
            _installState.value = MapInstallState.Failed(
                chosen.id,
                MapFailureReason.InsufficientStorage,
                "needs about ${(needed + reserve) / (1024 * 1024)} MB, ${free / (1024 * 1024)} MB free",
            )
            return
        }

        val archive = archiveFor(chosen)
        archive.parentFile?.mkdirs()
        _installState.value = MapInstallState.Downloading(chosen.id, 0, chosen.sizeBytes, null)

        val downloaded = downloader.download(
            url = chosen.downloadUrl,
            target = archive,
            expectedSha256 = chosen.sha256,
        ) { bytes, total, rate ->
            _installState.value = MapInstallState.Downloading(chosen.id, bytes, total ?: chosen.sizeBytes, rate)
        }

        downloaded.onFailure { throwable ->
            Log.w(TAG, "map download failed", throwable)
            val reason = when {
                (throwable as? MapDownloader.MapHttpException)?.statusCode == 404 ->
                    MapFailureReason.NotPublished

                !hasNetwork() -> MapFailureReason.NoNetwork
                else -> MapFailureReason.DownloadFailed
            }
            _installState.value = MapInstallState.Failed(chosen.id, reason, throwable.message)
            return
        }

        val part = downloaded.getOrThrow()
        _installState.value = MapInstallState.Verifying(chosen.id)

        val failure = withContext(Dispatchers.IO) { MapInstaller.verificationFailure(part, chosen) }
        if (failure != null) {
            // A corrupt download is discarded, not kept: keeping it would make every future retry
            // resume into the same broken bytes.
            part.delete()
            Log.w(TAG, "rejected ${chosen.id}: $failure")
            _installState.value = MapInstallState.Failed(chosen.id, MapFailureReason.VerificationFailed, failure)
            return
        }

        val installed = withContext(Dispatchers.IO) {
            MapInstaller.install(part, archive, directoryFor(chosen), chosen)
        }
        _installState.value = installed
    }

    private fun readInstalledState(chosen: MapPackage): MapInstallState? {
        val directory = directoryFor(chosen)
        val marker = MapInstaller.markerFile(directory)
        if (!marker.exists()) {
            val partial = downloader.partialFor(archiveFor(chosen))
            return if (partial.exists()) {
                MapInstallState.Paused(chosen.id, partial.length(), chosen.sizeBytes)
            } else {
                null
            }
        }
        val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return MapInstallState.Installed(chosen.id, size, marker.lastModified())
    }

    fun directoryFor(chosen: MapPackage): File = File(storage.layout.maps, chosen.id)

    private fun archiveFor(chosen: MapPackage): File =
        File(storage.layout.maps, "${chosen.id}${MapInstaller.archiveExtension(chosen.downloadUrl)}")

    private fun hasNetwork(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "RoadguardMap"

        /**
         * Headroom multiplier over the published download size.
         *
         * A downloaded archive may be unpacked, so briefly both copies exist. Two times the
         * published size plus the standard reserve is the safe floor.
         */
        const val SPACE_SAFETY_MULTIPLIER = 2L
    }
}
