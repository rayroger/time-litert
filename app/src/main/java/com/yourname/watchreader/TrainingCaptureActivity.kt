package com.yourname.watchreader

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Training-data capture mode: periodically photographs one or more analog watches,
 * detects each watch face, saves an annotated full image with bounding boxes, and
 * saves a separate cropped image per detected watch so the output can be used as
 * training data for a future time-reading model.
 */
class TrainingCaptureActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TrainingCapture"
        private const val DEFAULT_INTERVAL_SECONDS = 5L
        private const val MIN_INTERVAL_SECONDS = 1L

        /**
         * Public sub-folder of Pictures/ where training captures are saved. Shared with
         * [FileServer], which scopes its listing/serving to this same folder, so both
         * classes reference [FileServer.TRAINING_DATA_DIR] rather than duplicating the value.
         */
        private val TRAINING_DATA_DIR = FileServer.TRAINING_DATA_DIR
    }

    private lateinit var previewView: PreviewView
    private lateinit var intervalInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var serverToggleButton: Button
    private lateinit var serverStatusText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var watchDetector: WatchDetector? = null
    private var captureLoopJob: Job? = null
    private var sessionTimestamp: String? = null
    private var fileServer: FileServer? = null

    private var captureCount = 0
    private var watchCount = 0

    /** Set when the user tapped Start but we first needed to request storage permission. */
    private var pendingStartAfterPermission = false

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                startCaptureLoop()
            }
        } else {
            pendingStartAfterPermission = false
            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val labelPaint = Paint().apply {
        color = Color.GREEN
        textSize = 48f
        style = Paint.Style.FILL
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_training_capture)
        title = getString(R.string.training_capture_title)

        previewView = findViewById(R.id.trainingPreviewView)
        intervalInput = findViewById(R.id.intervalInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.trainingStatusText)
        serverToggleButton = findViewById(R.id.serverToggleButton)
        serverStatusText = findViewById(R.id.serverStatusText)

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            watchDetector = WatchDetector(this, maxResults = WatchDetector.MAX_DETECTIONS_PER_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector", e)
            statusText.text = getString(R.string.error_template, e.message)
        }

        startButton.setOnClickListener { startCaptureLoop() }
        stopButton.setOnClickListener { stopCaptureLoop() }
        serverToggleButton.setOnClickListener { toggleFileServer() }

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCaptureLoop() {
        if (captureLoopJob?.isActive == true) return

        val intervalSeconds = intervalInput.text.toString().toLongOrNull()
        if (intervalSeconds == null || intervalSeconds < MIN_INTERVAL_SECONDS) {
            Toast.makeText(this, R.string.invalid_interval, Toast.LENGTH_LONG).show()
            return
        }

        if (!requestLegacyStoragePermissionIfNeeded()) {
            // The system permission dialog is now showing; resume automatically in the
            // storagePermissionLauncher callback once the user responds.
            pendingStartAfterPermission = true
            return
        }

        sessionTimestamp = createSessionTimestamp()
        captureCount = 0
        watchCount = 0

        startButton.isEnabled = false
        stopButton.isEnabled = true
        intervalInput.isEnabled = false

        captureLoopJob = lifecycleScope.launch {
            while (isActive) {
                captureOnce()
                delay(intervalSeconds * 1000)
            }
        }
    }

    private fun stopCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = null

        startButton.isEnabled = true
        stopButton.isEnabled = false
        intervalInput.isEnabled = true

        statusText.text = getString(R.string.training_status_stopped, captureCount, watchCount)
    }

    private suspend fun captureOnce() {
        val capture = imageCapture ?: return

        val bitmap = try {
            takePictureSuspend(capture)
        } catch (e: ImageCaptureException) {
            Log.e(TAG, "Photo capture failed: ${e.message}", e)
            statusText.text = getString(R.string.error_template, e.message)
            return
        }

        processCapturedBitmap(bitmap)
    }

    private suspend fun takePictureSuspend(capture: ImageCapture): Bitmap =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = imageProxyToBitmap(image)
                        image.close()
                        continuation.resumeWith(Result.success(bitmap))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWith(Result.failure(exception))
                    }
                }
            )
        }

    private suspend fun processCapturedBitmap(bitmap: Bitmap) {
        val detector = watchDetector
        val timestamp = sessionTimestamp ?: createSessionTimestamp().also { sessionTimestamp = it }
        val index = captureCount + 1

        val detections: List<WatchDetection> = withContext(Dispatchers.IO) {
            try {
                val found = detector?.detect(bitmap) ?: emptyList()
                found.forEachIndexed { i, d ->
                    Log.d(TAG, "capture $index detection ${i + 1}: ${d.category} score=${d.score} box=${d.box}")
                }

                // Draw bounding boxes/labels on a mutable copy; keep the original bitmap for crops.
                val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val canvas = Canvas(annotated)
                    found.forEachIndexed { i, d ->
                        canvas.drawRect(d.box, boxPaint)
                        canvas.drawText(
                            "Watch ${i + 1} (%.2f)".format(Locale.US, d.score),
                            d.box.left,
                            (d.box.top - 12f).coerceAtLeast(24f),
                            labelPaint
                        )
                    }
                    saveBitmap(annotated, timestamp, "capture_${index}_annotated.jpg")
                } finally {
                    annotated.recycle()
                }

                found.forEachIndexed { i, d ->
                    val crop = cropWatch(bitmap, d.box) ?: return@forEachIndexed
                    try {
                        saveBitmap(crop, timestamp, "capture_${index}_watch_${i + 1}.jpg")
                    } finally {
                        crop.recycle()
                    }
                }
                found
            } finally {
                bitmap.recycle()
            }
        }

        captureCount = index
        watchCount += detections.size

        statusText.text = getString(R.string.training_status_running, captureCount, watchCount) +
            "\n" + getString(
                R.string.training_save_location,
                "${Environment.DIRECTORY_PICTURES}/$TRAINING_DATA_DIR/$timestamp"
            )
    }

    private fun cropWatch(bitmap: Bitmap, box: RectF): Bitmap? {
        val left = box.left.toInt().coerceIn(0, bitmap.width)
        val top = box.top.toInt().coerceIn(0, bitmap.height)
        val right = box.right.toInt().coerceIn(0, bitmap.width)
        val bottom = box.bottom.toInt().coerceIn(0, bitmap.height)
        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Skipping crop with invalid bounds: $box")
            return null
        }

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /**
     * Saves [bitmap] as [fileName] inside the publicly-visible
     * `Pictures/WatchReaderTrainingData/<sessionTimestamp>` collection, so captures survive
     * app uninstall and are reachable from a file manager/gallery app without ADB.
     *
     * On Android 10+ this goes through [MediaStore]; on older versions it falls back to a
     * direct file write under the legacy public Pictures directory (requires
     * `WRITE_EXTERNAL_STORAGE`, requested via [requestLegacyStoragePermissionIfNeeded]).
     */
    private fun saveBitmap(bitmap: Bitmap, sessionTimestamp: String, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveBitmapToMediaStore(bitmap, sessionTimestamp, fileName)
            } else {
                saveBitmapLegacy(bitmap, sessionTimestamp, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $fileName", e)
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, sessionTimestamp: String, fileName: String) {
        val relativePath = "${Environment.DIRECTORY_PICTURES}/$TRAINING_DATA_DIR/$sessionTimestamp"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry for $fileName")

        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        } ?: throw IOException("Failed to open output stream for $fileName")

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun saveBitmapLegacy(bitmap: Bitmap, sessionTimestamp: String, fileName: String) {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            ?: throw IOException("Public Pictures directory is unavailable (external storage not mounted)")
        val dir = File(picturesDir, "$TRAINING_DATA_DIR/$sessionTimestamp")
        dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        // Make the new file immediately visible to gallery apps/file managers.
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
    }

    private fun createSessionTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Returns true if storage writes are already permitted, requesting the permission otherwise. */
    private fun requestLegacyStoragePermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return granted
    }

    private fun toggleFileServer() {
        if (fileServer == null) startFileServer() else stopFileServer()
    }

    private fun startFileServer() {
        try {
            val server = FileServer(applicationContext)
            server.start()
            fileServer = server
            val ip = getLocalIpAddress() ?: getString(R.string.server_ip_unknown)
            val url = "http://$ip:${FileServer.DEFAULT_PORT}/?token=${server.accessToken}"
            serverStatusText.text = getString(R.string.server_status_running, url)
            serverToggleButton.text = getString(R.string.action_stop_server)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start file server", e)
            serverStatusText.text = getString(R.string.error_template, e.message)
        }
    }

    private fun stopFileServer() {
        fileServer?.stop()
        fileServer = null
        serverStatusText.text = getString(R.string.server_status_stopped)
        serverToggleButton.text = getString(R.string.action_start_server)
    }

    /** Returns the device's first non-loopback IPv4 address, or null if none is found. */
    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine local IP address", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        captureLoopJob?.cancel()
        fileServer?.stop()
        cameraExecutor.shutdown()
        watchDetector?.close()
    }
}
