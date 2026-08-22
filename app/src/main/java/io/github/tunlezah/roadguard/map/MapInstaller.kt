package io.github.tunlezah.roadguard.map

import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Turns a downloaded archive into an installed map directory, atomically.
 *
 * The order is: verify the download, unpack (or move) it into a fresh directory, then write the
 * marker file **last**. The marker is what [MapRepository] treats as "installed", so a crash before
 * it is written leaves an unmarked directory that the next attempt simply replaces -- there is no
 * state in which the app believes it has a map it does not have.
 */
object MapInstaller {

    private const val TAG = "RoadguardMapInstall"
    private const val MARKER_NAME = ".roadguard-installed"

    fun markerFile(directory: File): File = File(directory, MARKER_NAME)

    fun archiveExtension(url: String): String {
        val name = url.substringAfterLast('/').substringBefore('?')
        return when {
            name.endsWith(".zip") -> ".zip"
            name.endsWith(".mbtiles") -> ".mbtiles"
            name.endsWith(".pmtiles") -> ".pmtiles"
            name.endsWith(".map") -> ".map"
            name.contains('.') -> "." + name.substringAfterLast('.')
            else -> ".bin"
        }
    }

    /**
     * Structural verification.
     *
     * A published checksum, when the source provides one, is already checked by the downloader.
     * Most OpenStreetMap extract mirrors publish none, so this is the second line of defence: the
     * file must be non-trivial, must match the published size when one was stated, and must begin
     * with the magic bytes its extension implies. That catches the realistic failure -- a truncated
     * download or an HTML error page saved as a map -- without pretending to be a checksum.
     */
    fun verify(file: File, chosen: MapPackage): Boolean {
        if (!file.exists() || file.length() < MIN_PLAUSIBLE_BYTES) {
            Log.w(TAG, "map archive is implausibly small: ${file.length()} bytes")
            return false
        }
        chosen.sizeBytes?.let { expected ->
            // Allow a small tolerance: some mirrors publish rounded sizes.
            val tolerance = (expected / 100).coerceAtLeast(64 * 1024)
            if (file.length() < expected - tolerance) {
                Log.w(TAG, "map archive is short: ${file.length()} of $expected bytes")
                return false
            }
        }
        return hasExpectedMagic(file, archiveExtension(chosen.downloadUrl))
    }

    /**
     * Checks the file's leading bytes against its declared type.
     *
     * `.mbtiles` is SQLite, `.pmtiles` has its own magic, `.zip` is PK, and a Mapsforge `.map`
     * begins with an ASCII magic string. An HTML error page passes none of them.
     */
    private fun hasExpectedMagic(file: File, extension: String): Boolean = runCatching {
        val header = ByteArray(MAGIC_BYTES)
        file.inputStream().use { it.read(header) }
        val text = String(header, Charsets.ISO_8859_1)
        when (extension) {
            ".zip" -> text.startsWith("PK")
            ".mbtiles" -> text.startsWith("SQLite format 3")
            ".pmtiles" -> text.startsWith("PMTiles")
            ".map" -> text.startsWith("mapsforge binary OSM")
            // Unknown container: reject only the obvious failure of an HTML error page.
            else -> !text.trimStart().startsWith("<", ignoreCase = true)
        }
    }.getOrDefault(false)

    /**
     * Installs a verified archive.
     *
     * A zip is unpacked; anything else is moved into the directory under its own name. Zip entries
     * are checked against path traversal before extraction, because the archive comes from the
     * network and a `../` entry could otherwise write outside the map directory.
     */
    fun install(part: File, archive: File, directory: File, chosen: MapPackage): MapInstallState {
        return runCatching {
            // Replace any previous install atomically from the app's point of view: build the new
            // directory beside the old one, then swap.
            val staging = File(directory.parentFile, "${directory.name}.staging")
            staging.deleteRecursively()
            staging.mkdirs()

            if (archiveExtension(chosen.downloadUrl) == ".zip") {
                if (!unzip(part, staging)) {
                    staging.deleteRecursively()
                    return MapInstallState.Failed(chosen.id, MapFailureReason.VerificationFailed, "archive could not be unpacked")
                }
                part.delete()
            } else {
                val target = File(staging, archive.name)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            }

            markerFile(staging).writeText(
                buildString {
                    appendLine(chosen.id)
                    appendLine(chosen.displayName)
                    appendLine(chosen.licence)
                    appendLine(chosen.attribution)
                },
            )

            directory.deleteRecursively()
            if (!staging.renameTo(directory)) {
                staging.copyRecursively(directory, overwrite = true)
                staging.deleteRecursively()
            }

            val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            MapInstallState.Installed(chosen.id, size, System.currentTimeMillis())
        }.getOrElse { throwable ->
            Log.e(TAG, "map install failed", throwable)
            MapInstallState.Failed(chosen.id, MapFailureReason.VerificationFailed, throwable.message)
        }
    }

    private fun unzip(archive: File, into: File): Boolean = runCatching {
        val canonicalRoot = into.canonicalPath
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(into, entry.name)
                // Reject any entry that would escape the destination directory.
                if (!target.canonicalPath.startsWith(canonicalRoot + File.separator) &&
                    target.canonicalPath != canonicalRoot
                ) {
                    Log.w(TAG, "rejected zip entry outside the map directory: ${entry.name}")
                    return false
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
        true
    }.getOrElse {
        Log.w(TAG, "unzip failed", it)
        false
    }

    private const val MIN_PLAUSIBLE_BYTES = 256L * 1024
    private const val MAGIC_BYTES = 32
}
