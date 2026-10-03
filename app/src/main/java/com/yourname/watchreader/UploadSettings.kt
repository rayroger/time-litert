package com.yourname.watchreader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class UploadProtocol(val defaultPort: Int) {
    FTP(21), FTPS(21), SFTP(22)
}

data class UploadConfig(
    val enabled: Boolean,
    val protocol: UploadProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** Optional PEM/OpenSSH private key (SFTP only); [password] then acts as the key passphrase. */
    val privateKey: String,
    val remoteDir: String
) {
    val isUsable: Boolean get() = enabled && host.isNotBlank() && username.isNotBlank()
}

/**
 * Stores the upload configuration in SharedPreferences. The password and private key are
 * encrypted with an AES-GCM key held in the Android Keystore, so they are never written in
 * plain text and the key material cannot be extracted from the device.
 */
class UploadSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): UploadConfig {
        val protocol = runCatching { UploadProtocol.valueOf(prefs.getString(K_PROTOCOL, "") ?: "") }
            .getOrDefault(UploadProtocol.SFTP)
        return UploadConfig(
            enabled = prefs.getBoolean(K_ENABLED, false),
            protocol = protocol,
            host = prefs.getString(K_HOST, "") ?: "",
            port = prefs.getInt(K_PORT, protocol.defaultPort),
            username = prefs.getString(K_USER, "") ?: "",
            password = decrypt(prefs.getString(K_PASSWORD, null)),
            privateKey = decrypt(prefs.getString(K_KEY, null)),
            remoteDir = prefs.getString(K_DIR, "/") ?: "/"
        )
    }

    fun save(config: UploadConfig) {
        prefs.edit()
            .putBoolean(K_ENABLED, config.enabled)
            .putString(K_PROTOCOL, config.protocol.name)
            .putString(K_HOST, config.host)
            .putInt(K_PORT, config.port)
            .putString(K_USER, config.username)
            .putString(K_PASSWORD, encrypt(config.password))
            .putString(K_KEY, encrypt(config.privateKey))
            .putString(K_DIR, config.remoteDir)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        return try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 0, IV_SIZE))
            String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key was invalidated/removed; the user has to re-enter the secret.
            ""
        }
    }

    private companion object {
        const val PREFS = "upload_settings"
        const val K_ENABLED = "enabled"
        const val K_PROTOCOL = "protocol"
        const val K_HOST = "host"
        const val K_PORT = "port"
        const val K_USER = "username"
        const val K_PASSWORD = "password_enc"
        const val K_KEY = "private_key_enc"
        const val K_DIR = "remote_dir"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "watchreader_upload_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
