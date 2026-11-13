package com.yourname.watchreader

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads one queued file (a dial image or its annotation JSON) to the configured FTP/FTPS/SFTP
 * server off the main thread. Files are queued by [UploadScheduler]; on success the local queue
 * copy is deleted, on failure WorkManager retries with exponential backoff.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_PATH) ?: return@withContext Result.failure()
        val session = inputData.getString(KEY_SESSION) ?: ""
        val file = File(path)
        if (!file.exists()) return@withContext Result.failure()

        val config = UploadSettings(applicationContext).load()
        if (!config.isUsable) {
            Log.w(TAG, "Upload not configured; dropping ${file.name}")
            file.delete()
            return@withContext Result.failure(Data.Builder().putString(KEY_ERROR, "Upload not configured").build())
        }

        try {
            val remoteDir = joinRemote(config.remoteDir, session)
            when (config.protocol) {
                UploadProtocol.SFTP -> uploadSftp(config, remoteDir, file)
                else -> uploadFtp(config, remoteDir, file)
            }
            file.delete()
            deleteLocalCopy(inputData.getString(KEY_LOCAL))
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Upload of ${file.name} failed (attempt $runAttemptCount): ${e.message}", e)
            val error = Data.Builder().putString(KEY_ERROR, e.message ?: e.javaClass.simpleName).build()
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                file.delete()
                Result.failure(error)
            } else {
                Result.retry()
            }
        }
    }

    /**
     * Uploads over a shared, kept-open FTP/FTPS connection (one login for thousands of files
     * instead of connect + login + logout per file). If anything goes wrong on a reused
     * connection it is dropped and the upload is retried once on a fresh one; if persistent
     * connections keep failing, per-file connections are used as the fallback.
     */
    private fun uploadFtp(config: UploadConfig, remoteDir: String, file: File) {
        if (!FtpConnection.persistentDisabled()) {
            try {
                FtpConnection.upload(config, remoteDir, file, TIMEOUT_MS)
                FtpConnection.noteSuccess()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Persistent FTP upload of ${file.name} failed: ${e.message}")
                FtpConnection.close()
                if (FtpConnection.noteFailure()) {
                    Log.w(TAG, "Persistent FTP disabled for a while: using one connection per file")
                } else {
                    // Retry once right away on a brand-new connection.
                    try {
                        FtpConnection.upload(config, remoteDir, file, TIMEOUT_MS)
                        FtpConnection.noteSuccess()
                        return
                    } catch (e2: Exception) {
                        FtpConnection.close()
                        FtpConnection.noteFailure()
                        throw e2
                    }
                }
            }
        }
        FtpConnection.uploadOneShot(config, remoteDir, file, TIMEOUT_MS)
    }

    private fun uploadSftp(config: UploadConfig, remoteDir: String, file: File) {
        val jsch = JSch()
        if (config.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "watchreader",
                config.privateKey.toByteArray(Charsets.UTF_8),
                null,
                config.password.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            )
        }
        val session = jsch.getSession(config.username, config.host, config.port)
        if (config.privateKey.isBlank()) session.setPassword(config.password)
        // Host keys are not pinned: the user's own server is trusted without verification.
        session.setConfig("StrictHostKeyChecking", "no")
        session.timeout = TIMEOUT_MS
        try {
            session.connect(TIMEOUT_MS)
            val sftp = session.openChannel("sftp") as ChannelSftp
            try {
                sftp.connect(TIMEOUT_MS)
                var current = if (remoteDir.startsWith("/")) "/" else ""
                for (part in remoteDir.split("/").filter { it.isNotEmpty() }) {
                    current = if (current.isEmpty() || current.endsWith("/")) current + part else "$current/$part"
                    try {
                        sftp.cd(current)
                    } catch (e: SftpException) {
                        sftp.mkdir(current)
                        sftp.cd(current)
                    }
                }
                FileInputStream(file).use { sftp.put(it, remoteName(file), ChannelSftp.OVERWRITE) }
            } finally {
                sftp.disconnect()
            }
        } finally {
            session.disconnect()
        }
    }

    private fun remoteName(file: File) = file.name

    /**
     * The upload succeeded, so the public copy on the phone is no longer needed (the server holds
     * it). Files created by this app can be deleted from MediaStore without extra permission.
     */
    private fun deleteLocalCopy(local: String?) {
        if (local.isNullOrEmpty()) return
        try {
            if (local.startsWith("content://")) {
                applicationContext.contentResolver.delete(android.net.Uri.parse(local), null, null)
            } else {
                val f = File(local)
                if (f.delete()) {
                    android.media.MediaScannerConnection.scanFile(applicationContext, arrayOf(local), null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete local copy $local: ${e.message}")
        }
    }

    private fun joinRemote(base: String, session: String): String {
        val b = base.trim().ifEmpty { "/" }.trimEnd('/')
        return if (session.isEmpty()) b.ifEmpty { "/" } else "$b/$session"
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val WORK_TAG = "watchreader_upload"
        const val KEY_PATH = "path"
        const val KEY_SESSION = "session"
        const val KEY_LOCAL = "local"
        const val KEY_ERROR = "error"
        // The chain stays blocked while a file is retrying, which keeps ordering intact. A file
        // that never uploads keeps its copy in the public Pictures/Documents folders; only
        // successfully uploaded files are removed from the phone.
        private const val MAX_ATTEMPTS = 15
        private const val TIMEOUT_MS = 20_000
    }
}

/** Copies captured files into an app-private queue and schedules [UploadWorker] jobs for them. */
object UploadScheduler {
    private const val QUEUE_DIR = "upload_queue"

    fun isEnabled(context: Context): Boolean = UploadSettings(context).load().isUsable

    /** Queues [bytes] as [fileName] under [session] for upload. Safe to call from any thread. */
    fun enqueue(context: Context, session: String, fileName: String, bytes: ByteArray, localCopy: String? = null) {
        val dir = File(context.applicationContext.filesDir, "$QUEUE_DIR/$session").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(UploadWorker.KEY_PATH, file.absolutePath)
                    .putString(UploadWorker.KEY_SESSION, session)
                    .putString(UploadWorker.KEY_LOCAL, localCopy)
                    .build()
            )
            .addTag(UploadWorker.WORK_TAG)
            .build()
        // One chain per session so files upload strictly one at a time, in the order they were
        // saved (image first, then its JSON). Parallel jobs overloaded small FTP servers and left
        // images without their metadata (and the reverse).
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("upload_chain_$session", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
