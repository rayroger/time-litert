package com.yourname.watchreader

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Lets the user configure the optional FTP/FTPS/SFTP upload of captured images and annotations. */
class UploadSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload_settings)
        title = getString(R.string.upload_settings_title)

        val settings = UploadSettings(this)
        val enabled = findViewById<CheckBox>(R.id.uploadEnabled)
        val protocol = findViewById<Spinner>(R.id.uploadProtocol)
        val host = findViewById<EditText>(R.id.uploadHost)
        val port = findViewById<EditText>(R.id.uploadPort)
        val user = findViewById<EditText>(R.id.uploadUser)
        val password = findViewById<EditText>(R.id.uploadPassword)
        val key = findViewById<EditText>(R.id.uploadKey)
        val dir = findViewById<EditText>(R.id.uploadDir)
        val save = findViewById<Button>(R.id.uploadSave)

        protocol.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, UploadProtocol.values().map { it.name }
        )

        val cfg = settings.load()
        enabled.isChecked = cfg.enabled
        protocol.setSelection(cfg.protocol.ordinal)
        host.setText(cfg.host)
        port.setText(cfg.port.toString())
        user.setText(cfg.username)
        password.setText(cfg.password)
        key.setText(cfg.privateKey)
        dir.setText(cfg.remoteDir)

        save.setOnClickListener {
            val selected = UploadProtocol.values()[protocol.selectedItemPosition]
            val portValue = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
            if (portValue == null) {
                port.error = getString(R.string.upload_invalid_port)
                return@setOnClickListener
            }
            if (enabled.isChecked && (host.text.isNullOrBlank() || user.text.isNullOrBlank())) {
                host.error = getString(R.string.upload_host_user_required)
                return@setOnClickListener
            }
            settings.save(
                UploadConfig(
                    enabled = enabled.isChecked,
                    protocol = selected,
                    host = host.text.toString().trim(),
                    port = portValue,
                    username = user.text.toString().trim(),
                    password = password.text.toString(),
                    privateKey = key.text.toString(),
                    remoteDir = dir.text.toString().trim().ifEmpty { "/" }
                )
            )
            Toast.makeText(this, R.string.upload_settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
