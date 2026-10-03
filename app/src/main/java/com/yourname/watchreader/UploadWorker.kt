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
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
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

    private fun uploadFtp(config: UploadConfig, remoteDir: String, file: File) {
        val client = if (config.protocol == UploadProtocol.FTPS) FTPSClient() else FTPClient()
        client.connectTimeout = TIMEOUT_MS
        client.defaultTimeout = TIMEOUT_MS
        try {
            client.connect(config.host, config.port)
            client.soTimeout = TIMEOUT_MS
            client.setDataTimeout(java.time.Duration.ofMillis(TIMEOUT_MS.toLong()))
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw IOException("FTP server refused connection")
            if (!client.login(config.username, config.password)) throw IOException("FTP login failed")
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            var current = if (remoteDir.startsWith("/")) "/" else ""
            for (part in remoteDir.split("/").filter { it.isNotEmpty() }) {
                current = if (current.isEmpty() || current.endsWith("/")) current + part else "$current/$part"
                if (!client.changeWorkingDirectory(current)) {
                    if (!client.makeDirectory(current) || !client.changeWorkingDirectory(current)) {
                        throw IOException("Cannot create/enter remote directory $current")
                    }
                }
            }
            FileInputStream(file).use { input ->
                if (!client.storeFile(remoteName(file), input)) {
                    throw IOException("FTP upload failed: ${client.replyString?.trim()}")
                }
            }
            client.logout()
        } finally {
            try { if (client.isConnected) client.disconnect() } catch (_: IOException) { }
        }
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

    private fun joinRemote(base: String, session: String): String {
        val b = base.trim().ifEmpty { "/" }.trimEnd('/')
        return if (session.isEmpty()) b.ifEmpty { "/" } else "$b/$session"
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val WORK_TAG = "watchreader_upload"
        const val KEY_PATH = "path"
        const val KEY_SESSION = "session"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 5
        private const val TIMEOUT_MS = 20_000
    }
}

/** Copies captured files into an app-private queue and schedules [UploadWorker] jobs for them. */
object UploadScheduler {
    private const val QUEUE_DIR = "upload_queue"

    fun isEnabled(context: Context): Boolean = UploadSettings(context).load().isUsable

    /** Queues [bytes] as [fileName] under [session] for upload. Safe to call from any thread. */
    fun enqueue(context: Context, session: String, fileName: String, bytes: ByteArray) {
        val dir = File(context.applicationContext.filesDir, "$QUEUE_DIR/$session").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(UploadWorker.KEY_PATH, file.absolutePath)
                    .putString(UploadWorker.KEY_SESSION, session)
                    .build()
            )
            .addTag(UploadWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("upload_${session}_$fileName", ExistingWorkPolicy.REPLACE, request)
    }
}
