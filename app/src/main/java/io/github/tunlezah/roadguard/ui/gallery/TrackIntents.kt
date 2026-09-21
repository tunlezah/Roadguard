package io.github.tunlezah.roadguard.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a trip's GPX track to another app, at the user's explicit request.
 *
 * Both paths go through Roadguard's `FileProvider`, which exposes nothing outside its own
 * directories, and both are a tap on a button: Roadguard never sends a track anywhere by itself.
 *
 * "Open" is a view intent with the GPX MIME type, which CoMaps and Organic Maps both declare
 * filters for. If no installed app answers it, the share sheet is offered instead of an empty
 * chooser -- the manifest's `<queries>` block is what lets Roadguard ask that question.
 */
object TrackIntents {

    const val MIME_TYPE = "application/gpx+xml"

    /** How the hand-off went, for the confirmation message. */
    enum class Outcome { Opened, SharedInstead, Failed }

    fun open(context: Context, file: File): Outcome {
        val uri = runCatching { uriFor(context, file) }.getOrElse { return Outcome.Failed }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val handlers = runCatching {
            context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        if (handlers.isEmpty()) {
            return if (share(context, file)) Outcome.SharedInstead else Outcome.Failed
        }
        return runCatching {
            context.startActivity(Intent.createChooser(view, "Open track with"))
            Outcome.Opened
        }.getOrDefault(Outcome.Failed)
    }

    fun share(context: Context, file: File): Boolean = runCatching {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share this track"))
        true
    }.getOrDefault(false)

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
