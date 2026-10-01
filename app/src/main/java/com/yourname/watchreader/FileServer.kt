package com.yourname.watchreader

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.text.Html
import fi.iki.elonen.NanoHTTPD

/**
 * Minimal local HTTP server that lists and serves the training-data images saved to the
 * public `Pictures/WatchReaderTrainingData` collection (see [TrainingCaptureActivity]), so a
 * PC on the same Wi-Fi network can browse and download them in a browser (or with `curl`/
 * `wget`) without needing `adb pull`.
 *
 * Files are read back through [android.content.ContentResolver] / [MediaStore] rather than
 * raw file paths, so this works the same way whether the captures were written via MediaStore
 * (Android 10+) or to the legacy public Pictures directory (Android 9 and below).
 */
class FileServer(
    private val context: Context,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    private data class MediaEntry(val id: Long, val displayName: String)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" -> serveIndex()
            uri.startsWith("/file/") -> serveFile(uri.removePrefix("/file/"))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveIndex(): Response {
        val entries = queryEntries()
        val html = buildString {
            append("<html><head><meta charset=\"utf-8\"></head><body>")
            append("<h1>Watch Reader Training Data</h1>")
            if (entries.isEmpty()) {
                append("<p>No captures saved yet.</p>")
            } else {
                append("<ul>")
                entries.forEach { entry ->
                    val safeName = Html.escapeHtml(entry.displayName)
                    append("<li><a href=\"/file/${entry.id}\">$safeName</a></li>")
                }
                append("</ul>")
            }
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveFile(idSegment: String): Response {
        val id = idSegment.toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file id")

        val mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val stream = try {
            context.contentResolver.openInputStream(mediaUri)
        } catch (e: Exception) {
            null
        } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        return newChunkedResponse(Response.Status.OK, "image/jpeg", stream)
    }

    /** Queries MediaStore for every image saved under the training-data collection. */
    private fun queryEntries(): List<MediaEntry> {
        val entries = mutableListOf<MediaEntry>()
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn = if (useRelativePath) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            MediaStore.Images.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            pathColumn
        )
        val selection = "$pathColumn LIKE ?"
        val selectionArgs = arrayOf("%$RELATIVE_PATH_MARKER%")

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    entries += MediaEntry(cursor.getLong(idCol), cursor.getString(nameCol))
                }
            }
        } catch (e: Exception) {
            // MediaStore query can fail on some OEM devices/columns; return what we have.
        }
        return entries
    }

    companion object {
        const val DEFAULT_PORT = 8080

        /** Marker shared with [TrainingCaptureActivity]'s storage location. */
        const val RELATIVE_PATH_MARKER = "WatchReaderTrainingData"
    }
}
