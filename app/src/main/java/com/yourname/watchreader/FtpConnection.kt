package com.yourname.watchreader

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * A process-wide FTP/FTPS connection that stays logged in between files. Uploads are serialized,
 * the working directory is tracked so it is only changed when the session folder changes, and the
 * connection is reopened whenever the server dropped it or the settings changed.
 */
object FtpConnection {
    private val lock = Any()
    private var client: FTPClient? = null
    private var key: String? = null
    private var cwd: String? = null
    private var lastUse = 0L
    private var consecutiveFailures = 0
    private var disabledUntil = 0L

    private const val IDLE_PROBE_MS = 15_000L
    private const val MAX_FAILURES = 3
    private const val DISABLE_MS = 5 * 60_000L

    fun persistentDisabled(): Boolean = System.currentTimeMillis() < disabledUntil

    fun noteSuccess() { consecutiveFailures = 0 }

    /** Returns true when persistent mode has just been switched off for [DISABLE_MS]. */
    fun noteFailure(): Boolean {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_FAILURES) {
            consecutiveFailures = 0
            disabledUntil = System.currentTimeMillis() + DISABLE_MS
            return true
        }
        return false
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        val c = client
        client = null; key = null; cwd = null
        try { if (c != null && c.isConnected) { try { c.logout() } catch (_: Exception) { }; c.disconnect() } } catch (_: IOException) { }
    }

    fun upload(config: UploadConfig, remoteDir: String, file: File, timeoutMs: Int) = synchronized(lock) {
        val k = "${config.protocol}|${config.host}|${config.port}|${config.username}|${config.password.hashCode()}"
        var c = client
        if (c != null && (key != k || !c.isConnected)) { closeLocked(); c = null }
        // After an idle gap make sure the server did not time the session out.
        if (c != null && System.currentTimeMillis() - lastUse > IDLE_PROBE_MS) {
            val alive = try { c.sendNoOp() } catch (_: Exception) { false }
            if (!alive) { closeLocked(); c = null }
        }
        if (c == null) {
            c = open(config, timeoutMs)
            client = c; key = k; cwd = null
        }
        try {
            if (cwd != remoteDir) {
                enterDir(c, remoteDir)
                cwd = remoteDir
            }
            store(c, file)
            lastUse = System.currentTimeMillis()
        } catch (e: Exception) {
            closeLocked()
            throw e
        }
    }

    fun uploadOneShot(config: UploadConfig, remoteDir: String, file: File, timeoutMs: Int) {
        val c = open(config, timeoutMs)
        try {
            enterDir(c, remoteDir)
            store(c, file)
            c.logout()
        } finally {
            try { if (c.isConnected) c.disconnect() } catch (_: IOException) { }
        }
    }

    private fun open(config: UploadConfig, timeoutMs: Int): FTPClient {
        val c = if (config.protocol == UploadProtocol.FTPS) FTPSClient() else FTPClient()
        c.connectTimeout = timeoutMs
        c.defaultTimeout = timeoutMs
        try {
            c.connect(config.host, config.port)
            c.soTimeout = timeoutMs
            c.setDataTimeout(java.time.Duration.ofMillis(timeoutMs.toLong()))
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException("FTP server refused connection")
            if (!c.login(config.username, config.password)) throw IOException("FTP login failed")
            if (c is FTPSClient) {
                c.execPBSZ(0)
                c.execPROT("P")
            }
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            return c
        } catch (e: Exception) {
            try { if (c.isConnected) c.disconnect() } catch (_: IOException) { }
            throw e
        }
    }

    private fun enterDir(c: FTPClient, remoteDir: String) {
        var current = if (remoteDir.startsWith("/")) "/" else ""
        for (part in remoteDir.split("/").filter { it.isNotEmpty() }) {
            current = if (current.isEmpty() || current.endsWith("/")) current + part else "$current/$part"
            if (!c.changeWorkingDirectory(current)) {
                if (!c.makeDirectory(current) || !c.changeWorkingDirectory(current)) {
                    throw IOException("Cannot create/enter remote directory $current")
                }
            }
        }
    }

    private fun store(c: FTPClient, file: File) {
        FileInputStream(file).use { input ->
            if (!c.storeFile(file.name, input)) {
                throw IOException("FTP upload failed: ${c.replyString?.trim()}")
            }
        }
    }
}
