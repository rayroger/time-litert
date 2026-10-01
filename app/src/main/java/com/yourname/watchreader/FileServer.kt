package com.yourname.watchreader

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import java.util.UUID

/**
 * Minimal local HTTP server that lists and serves the training-data images saved to the
 * public `Pictures/WatchReaderTrainingData` collection (see [TrainingCaptureActivity]), so a
 * PC on the same Wi-Fi network can browse and download them in a browser (or with `curl`/
 * `wget`) without needing `adb pull`.
 *
 * Files are read back through [android.content.ContentResolver] / [MediaStore] rather than
 * raw file paths, so this works the same way whether the captures were written via MediaStore
 * (Android 10+) or to the legacy public Pictures directory (Android 9 and below).
 *
 * Every request must include the generated [accessToken] as a `token` query parameter (only
 * needed for the very first request; the server then issues a session cookie so later
 * requests from the same browser don't need to repeat the token in the URL) so that other
 * devices on the same Wi-Fi network can't silently browse/download captures.
 */
class FileServer(
    private val context: Context,
    port: Int = DEFAULT_PORT,
    val accessToken: String = UUID.randomUUID().toString()
) : NanoHTTPD(port) {

    private data class MediaEntry(val id: Long, val displayName: String)

    /**
     * Separate, independently-generated identifier used only for the browser session cookie.
     * Kept distinct from [accessToken] so that if the cookie is ever exposed (e.g. captured on
     * an unencrypted network), it doesn't also reveal the original token embedded in the
     * printed/shared URL.
     */
    private val sessionToken: String = UUID.randomUUID().toString()

    override fun serve(session: IHTTPSession): Response {
        val cookieToken = extractCookieToken(session.headers["cookie"])
        val queryToken = session.parms["token"]
        val authenticatedByCookie = isValidSessionToken(cookieToken)
        val authenticatedByQuery = isValidToken(queryToken)

        if (!authenticatedByCookie && !authenticatedByQuery) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden: missing or invalid token")
        }

        val uri = session.uri
        val response = when {
            uri == "/" -> serveIndex()
            uri.startsWith("/file/") -> serveFile(uri.removePrefix("/file/"))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        // The first request typically authenticates via the token embedded in the shared
        // link. Once that happens, hand the browser a session cookie (a separate identifier
        // from accessToken, see [sessionToken]) so subsequent page/file requests from the
        // same browser don't need to keep carrying the original token in the URL (where it
        // could otherwise leak into browser history, proxy logs, or Referer headers).
        if (!authenticatedByCookie && authenticatedByQuery) {
            response.addHeader("Set-Cookie", "$SESSION_COOKIE_NAME=$sessionToken; Path=/; HttpOnly; SameSite=Strict")
        }
        return response
    }

    /** Constant-time token comparison to avoid leaking the token via response-timing side channels. */
    private fun isValidToken(provided: String?): Boolean {
        if (provided == null) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            accessToken.toByteArray(Charsets.UTF_8)
        )
    }

    /** Constant-time comparison against the session-only cookie identifier (see [sessionToken]). */
    private fun isValidSessionToken(provided: String?): Boolean {
        if (provided == null) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            sessionToken.toByteArray(Charsets.UTF_8)
        )
    }

    private fun extractCookieToken(cookieHeader: String?): String? {
        if (cookieHeader == null) return null
        return cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
            ?.substringAfter("=")
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
                    // No token query param here: the session cookie set on first load (see
                    // [serve]) authenticates these follow-up requests instead.
                    append("<li><a href=\"/file/${entry.id}\">$safeName</a></li>")
                }
                append("</ul>")
            }
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun serveFile(idSegment: String): Response {
        // NanoHTTPD's session.uri already excludes the query string (available separately
        // via session.parms), so idSegment is just the path segment after "/file/".
        val id = idSegment.toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file id")

        // Make sure the requested id actually belongs to the training-data collection before
        // opening it, so a client with a valid token can't enumerate ids to read arbitrary
        // images (e.g. personal photos) elsewhere on the device.
        if (!isWithinTrainingData(id)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Not part of the training-data collection")
        }

        val mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val stream = try {
            context.contentResolver.openInputStream(mediaUri)
        } catch (e: Exception) {
            null
        } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        return newChunkedResponse(Response.Status.OK, "image/jpeg", stream)
    }

    /** Returns true only if [id] refers to an image inside the training-data collection. */
    private fun isWithinTrainingData(id: Long): Boolean {
        val (pathColumn, pattern) = pathColumnAndPattern()
        val selection = "${MediaStore.Images.Media._ID} = ? AND $pathColumn LIKE ?"
        val selectionArgs = arrayOf(id.toString(), pattern)

        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the MediaStore column to match against the training-data folder, together with
     * the exact `LIKE` pattern anchored to the `Pictures/WatchReaderTrainingData/` prefix
     * (rather than a bare substring, so we don't accidentally pick up unrelated media whose
     * path happens to contain the same marker text). Shared by [isWithinTrainingData] and
     * [queryEntries] so the two queries can't drift apart over time.
     */
    private fun pathColumnAndPattern(): Pair<String, String> {
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn = if (useRelativePath) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val pattern = if (useRelativePath) {
            "${Environment.DIRECTORY_PICTURES}/$TRAINING_DATA_DIR/%"
        } else {
            "%/${Environment.DIRECTORY_PICTURES}/$TRAINING_DATA_DIR/%"
        }
        return pathColumn to pattern
    }

    /** Queries MediaStore for every image saved under the training-data collection. */
    private fun queryEntries(): List<MediaEntry> {
        val entries = mutableListOf<MediaEntry>()
        val (pathColumn, pattern) = pathColumnAndPattern()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            pathColumn
        )
        val selection = "$pathColumn LIKE ?"
        val selectionArgs = arrayOf(pattern)

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

        /** Public sub-folder of Pictures/ shared with [TrainingCaptureActivity]'s storage location. */
        const val TRAINING_DATA_DIR = "WatchReaderTrainingData"

        private const val SESSION_COOKIE_NAME = "watchreader_session"
    }
}
