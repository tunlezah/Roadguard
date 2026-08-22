package io.github.tunlezah.roadguard.map

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Resumable HTTP download for offline map data.
 *
 * The requirements come straight from the product brief: automatic installation, progress, retry,
 * pause and resume, verification and corruption recovery. The implementation is deliberately
 * small and dependency-light:
 *
 *  * **Resume** uses a byte-`Range` request against the partial file's length. A server that
 *    answers 200 instead of 206 is handled by restarting from zero rather than silently
 *    concatenating a second copy of the file onto the first, which is the classic bug here.
 *  * **Progress** is reported by callback, throttled to whole percent, so a 700 MB download does
 *    not push thousands of state updates through Compose.
 *  * **Integrity** is a published SHA-256 when the source publishes one. Most OpenStreetMap
 *    extract mirrors do not, so the caller also verifies structurally -- a download that is the
 *    wrong length or that the map renderer cannot open is rejected either way.
 *  * **Atomicity** is the caller's job via [MapInstaller]: this class only ever writes to a
 *    `.part` file.
 */
class MapDownloader(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Downloads [url] into `[target].part`, resuming if a partial file exists.
     *
     * @param onProgress called with (bytesDownloaded, totalBytes or null, bytesPerSecond or null).
     * @return the completed `.part` file, or a failure. Cancellation of the calling coroutine
     *   leaves the partial file in place so the next attempt resumes.
     */
    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
        onProgress: (Long, Long?, Long?) -> Unit = { _, _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val partial = File(target.parentFile, target.name + PART_SUFFIX)
            partial.parentFile?.mkdirs()
            var existing = if (partial.exists()) partial.length() else 0L

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw MapHttpException(response.code, url)
                }
                // A 200 in reply to a Range request means the server ignored it, so anything we
                // already have must be discarded rather than appended to.
                if (existing > 0 && response.code != 206) {
                    Log.i(TAG, "server ignored Range (HTTP ${response.code}); restarting download")
                    partial.delete()
                    existing = 0L
                }

                val body = response.body ?: throw IOException("empty response body")
                val remaining = body.contentLength().takeIf { it >= 0 }
                val total = remaining?.let { existing + it }

                RandomAccessFile(partial, "rw").use { output ->
                    output.seek(existing)
                    body.source().use { source ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = existing
                        var lastReportedPercent = -1
                        var lastReportAtMs = System.currentTimeMillis()
                        var bytesSinceReport = 0L

                        while (currentCoroutineContext().isActive) {
                            val read = source.read(buffer, 0, buffer.size)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            bytesSinceReport += read

                            val percent = total?.takeIf { it > 0 }
                                ?.let { ((downloaded * 100) / it).toInt() }
                                ?: -1
                            val nowMs = System.currentTimeMillis()
                            val elapsed = nowMs - lastReportAtMs
                            if (percent != lastReportedPercent || elapsed >= PROGRESS_INTERVAL_MS) {
                                val rate = if (elapsed > 0) bytesSinceReport * 1000 / elapsed else null
                                onProgress(downloaded, total, rate)
                                lastReportedPercent = percent
                                lastReportAtMs = nowMs
                                bytesSinceReport = 0
                            }
                        }
                        // The write is flushed to storage before the file is considered complete:
                        // an unflushed tail would fail verification on the next start for no
                        // reason the user could act on.
                        output.fd.sync()
                    }
                }

                if (!currentCoroutineContext().isActive) throw IOException("download cancelled")

                if (expectedSha256 != null) {
                    val actual = sha256(partial)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        partial.delete()
                        throw IOException("checksum mismatch: expected $expectedSha256, got $actual")
                    }
                }
                partial
            }
        }
    }

    fun partialFor(target: File): File = File(target.parentFile, target.name + PART_SUFFIX)

    fun discardPartial(target: File) {
        runCatching { partialFor(target).delete() }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A non-2xx response, carrying the status so the caller can distinguish 404 from 5xx. */
    class MapHttpException(val statusCode: Int, url: String) :
        IOException("HTTP $statusCode for $url")

    companion object {
        private const val TAG = "RoadguardMapDownload"
        const val PART_SUFFIX = ".part"
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 500L

        /**
         * Identifies Roadguard to the tile/extract host.
         *
         * OpenStreetMap-derived data sources ask automated clients to identify themselves; a
         * generic or absent User-Agent is grounds for being blocked, and being blocked would break
         * first-run map installation for every user.
         */
        const val USER_AGENT = "Roadguard/1.0 (offline dashcam; +https://github.com/tunlezah/Roadguard)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
