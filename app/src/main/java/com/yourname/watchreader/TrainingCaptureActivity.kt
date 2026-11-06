package com.yourname.watchreader

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
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

        /**
         * Intent extras for starting a session without touching the screen, e.g.
         * `adb shell am start -n com.yourname.watchreader/.TrainingCaptureActivity
         *      --ez auto_start true --el interval_seconds 15 --el duration_seconds 300`
         */
        const val EXTRA_AUTO_START = "auto_start"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        private const val CAMERA_READY_TIMEOUT_MS = 15_000L
        private const val LIVE_INTERVAL_MS = 700L
        private const val LIVE_MAX_SIDE = 1280
        /** Focus on each watch in turn and take one photo per watch. */
        private const val PER_WATCH_FOCUS = true
        private const val FOCUS_SETTLE_MS = 500L
        /** While capturing, the screen dims to its minimum after this long without a touch. */
        private const val IDLE_DIM_AFTER_MS = 2 * 60 * 1000L
        private const val DIM_BRIGHTNESS = 0.01f
    }

    private lateinit var previewView: PreviewView
    private lateinit var intervalInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var serverToggleButton: Button
    private lateinit var serverStatusText: TextView
    private lateinit var uploadSettingsButton: Button
    private lateinit var uploadStatusText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var liveOverlay: LiveOverlayView
    private lateinit var liveStatusText: TextView
    private lateinit var settingsButton: Button
    private lateinit var settingsPanel: View

    /** The detector is not thread-safe: the live preview and the capture loop take turns. */
    private val detectorMutex = Mutex()

    // Power saving: while a session runs and nobody touches the phone, the screen is dimmed to its
    // minimum behind a black cover and live detection is paused. The camera and the scheduled
    // captures keep running (turning the screen fully off would stop the camera). Any touch wakes it.
    private val idleHandler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { enterDim() }
    private var dimmed = false
    private var dimCover: TextView? = null

    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null
    private var watchDetector: WatchDetector? = null
    private var clockReader: ClockReader? = null
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
        uploadSettingsButton = findViewById(R.id.uploadSettingsButton)
        uploadStatusText = findViewById(R.id.uploadStatusText)
        liveOverlay = findViewById(R.id.liveOverlay)
        liveStatusText = findViewById(R.id.liveStatusText)
        settingsButton = findViewById(R.id.settingsButton)
        settingsPanel = findViewById(R.id.settingsPanel)

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            watchDetector = WatchDetector(this, maxResults = WatchDetector.MAX_DETECTIONS_PER_CAPTURE)
            clockReader = ClockReader.create(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector", e)
            statusText.text = getString(R.string.error_template, e.message)
        }

        startButton.setOnClickListener { startCaptureLoop() }
        stopButton.setOnClickListener { stopCaptureLoop() }
        serverToggleButton.setOnClickListener { toggleFileServer() }
        uploadSettingsButton.setOnClickListener {
            startActivity(Intent(this, UploadSettingsActivity::class.java))
        }
        settingsButton.setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        observeUploads()
        startLiveDetection()
        installDimCover()

        // Keep the screen awake so unattended sessions are not interrupted by the lock screen.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startCamera()
        // Only on a fresh launch: if Android re-creates the activity (e.g. rotation) while a
        // session is running, re-reading the launch extras would start a second session.
        if (savedInstanceState == null) handleAutoStart(intent)
    }

    /** Starts a timed session when launched with [EXTRA_AUTO_START] (used for adb-driven runs). */
    private fun handleAutoStart(launchIntent: Intent?) {
        if (launchIntent?.getBooleanExtra(EXTRA_AUTO_START, false) != true) return
        val interval = launchIntent.getLongExtra(EXTRA_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
        val duration = launchIntent.getLongExtra(EXTRA_DURATION_SECONDS, 0L)
        intervalInput.setText(interval.toString())

        lifecycleScope.launch {
            // The camera binds asynchronously; wait for it so the first frame is not skipped.
            var waited = 0L
            while (imageCapture == null && waited < CAMERA_READY_TIMEOUT_MS) {
                delay(200)
                waited += 200
            }
            if (imageCapture == null) {
                statusText.text = getString(R.string.error_template, "Camera not ready (permission?)")
                return@launch
            }
            startCaptureLoop()
            if (duration > 0) {
                delay(duration * 1000)
                stopCaptureLoop()
            }
        }
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

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraControl = null
                imageCapture = null
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, capture
                )
                cameraControl = camera.cameraControl
                imageCapture = capture
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

        startButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        settingsPanel.visibility = View.GONE
        intervalInput.isEnabled = false

        captureLoopJob = lifecycleScope.launch {
            while (isActive) {
                captureOnce()
                delay(intervalSeconds * 1000)
            }
        }
        scheduleDim()
    }

    // ---- idle dimming ----------------------------------------------------------------------
    private fun installDimCover() {
        val cover = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            textSize = 16f
            text = getString(R.string.dim_touch_to_wake)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { wakeFromDim() }
        }
        (findViewById<ViewGroup>(android.R.id.content)).addView(
            cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        dimCover = cover
    }

    private fun scheduleDim() {
        idleHandler.removeCallbacks(dimRunnable)
        if (captureLoopJob?.isActive == true) idleHandler.postDelayed(dimRunnable, IDLE_DIM_AFTER_MS)
    }

    private fun enterDim() {
        if (captureLoopJob?.isActive != true) return
        dimmed = true
        dimCover?.visibility = View.VISIBLE
        window.attributes = window.attributes.also { it.screenBrightness = DIM_BRIGHTNESS }
    }

    private fun wakeFromDim() {
        if (dimmed) {
            dimmed = false
            dimCover?.visibility = View.GONE
            window.attributes = window.attributes.also {
                it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        scheduleDim()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        wakeFromDim()
    }

    private fun stopCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = null
        idleHandler.removeCallbacks(dimRunnable)
        wakeFromDim()

        startButton.visibility = View.VISIBLE
        stopButton.visibility = View.GONE
        intervalInput.isEnabled = true

        statusText.text = getString(R.string.training_status_stopped, captureCount, watchCount)
    }

    /** (battery percent, charging) from the sticky battery broadcast; (null, null) if unavailable. */
    private fun batteryState(): Pair<Int?, Boolean?> = try {
        val i = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        Pair(pct, charging)
    } catch (e: Exception) {
        Pair(null, null)
    }

    private suspend fun captureOnce() {
        val capture = imageCapture ?: return
        val index = captureCount + 1

        // Focus on every watch in turn (like tapping each one on the screen) and take one photo per
        // watch, so the outer watches are sharp too. Falls back to a single centre-focused photo.
        val targets = if (PER_WATCH_FOCUS) detectViewBoxes() else emptyList()
        if (targets.isEmpty()) {
            cameraControl?.focusAndMeterAtCenter(previewView)
            if (imageCapture !== capture) return
            val bitmap = try {
                takePictureSuspend(capture)
            } catch (e: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${e.message}", e)
                statusText.text = getString(R.string.error_template, e.message)
                return
            }
            finishCapture(index, processCapturedBitmap(bitmap, index, null))
            return
        }

        var saved = 0
        for ((i, box) in targets.withIndex()) {
            if (imageCapture !== capture) return
            cameraControl?.focusAndMeterAt(previewView, box.centerX(), box.centerY())
            delay(FOCUS_SETTLE_MS)
            val bitmap = try {
                takePictureSuspend(capture)
            } catch (e: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${e.message}", e)
                continue
            }
            saved += processCapturedBitmap(bitmap, index, i + 1)
        }
        finishCapture(index, saved)
    }

    private fun finishCapture(index: Int, crops: Int) {
        val timestamp = sessionTimestamp ?: return
        captureCount = index
        watchCount += crops
        statusText.text = getString(R.string.training_status_running, captureCount, watchCount) +
            "\n" + getString(
                R.string.training_save_location,
                "${Environment.DIRECTORY_PICTURES}/$TRAINING_DATA_DIR/$timestamp"
            )
    }

    /** Watch boxes (preview view coordinates, left to right) found in the current preview frame. */
    private suspend fun detectViewBoxes(): List<RectF> {
        val detector = watchDetector ?: return emptyList()
        if (previewView.width == 0) return emptyList()
        val frame = previewView.bitmap ?: return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                detectorMutex.withLock {
                    val longSide = maxOf(frame.width, frame.height)
                    val small = if (longSide > LIVE_MAX_SIDE) {
                        val f = LIVE_MAX_SIDE.toFloat() / longSide
                        Bitmap.createScaledBitmap(frame, (frame.width * f).toInt(), (frame.height * f).toInt(), true)
                    } else frame
                    try {
                        val sx = previewView.width.toFloat() / small.width
                        val sy = previewView.height.toFloat() / small.height
                        detector.detect(small)
                            .sortedWith(compareBy({ it.box.centerX() }, { it.box.centerY() }))
                            .map { RectF(it.box.left * sx, it.box.top * sy, it.box.right * sx, it.box.bottom * sy) }
                    } finally {
                        if (small !== frame) small.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preview detection failed: ${e.message}")
            emptyList()
        } finally {
            frame.recycle()
        }
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

    /** Detects, saves the annotated photo and the crops (only dial [onlyDial] when set); returns how many crops were saved. */
    private suspend fun processCapturedBitmap(bitmap: Bitmap, index: Int, onlyDial: Int?): Int {
        val detector = watchDetector
        val timestamp = sessionTimestamp ?: createSessionTimestamp().also { sessionTimestamp = it }
        val annotatedName = if (onlyDial == null) "capture_${index}_annotated.jpg" else "capture_${index}_annotated_w$onlyDial.jpg"
        var savedCrops = 0

        val detections: List<WatchDetection> = withContext(Dispatchers.IO) {
            try {
                // Number watches left to right (then top to bottom) instead of by detector score,
                // so with a fixed camera the same physical watch keeps the same dial_index.
                val found = detectorMutex.withLock { detector?.detect(bitmap) ?: emptyList() }
                    .sortedWith(compareBy({ it.box.centerX() }, { it.box.centerY() }))
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
                    saveBitmap(annotated, timestamp, annotatedName)
                } finally {
                    annotated.recycle()
                }

                val captureMillis = System.currentTimeMillis()
                val battery = batteryState()
                found.forEachIndexed { i, d ->
                    if (onlyDial != null && i + 1 != onlyDial) return@forEachIndexed
                    val (crop, cropRect) = cropWatch(bitmap, d.box) ?: return@forEachIndexed
                    try {
                        val imageName = "capture_${index}_watch_${i + 1}.jpg"
                        saveBitmap(crop, timestamp, imageName)
                        saveAnnotation(
                            WatchAnnotation(
                                imageFilename = imageName,
                                imageWidth = crop.width,
                                imageHeight = crop.height,
                                captureTimestampMillis = captureMillis,
                                captureTimestampIso = formatIso(captureMillis),
                                session = timestamp,
                                captureIndex = index,
                                dialIndex = i + 1,
                                sourceImageFilename = annotatedName,
                                sourceImageWidth = bitmap.width,
                                sourceImageHeight = bitmap.height,
                                dialBoxSource = BoxPx(cropRect.left.toFloat(), cropRect.top.toFloat(), cropRect.right.toFloat(), cropRect.bottom.toFloat()),
                                dialBoxCrop = BoxPx(0f, 0f, crop.width.toFloat(), crop.height.toFloat()),
                                detectionScore = d.score,
                                detectionCategory = d.category,
                                modelName = WatchDetector.MODEL_NAME,
                                modelVersion = appVersionName(),
                                batteryPercent = battery.first,
                                charging = battery.second
                            ),
                            timestamp,
                            "capture_${index}_watch_${i + 1}.json"
                        )
                        savedCrops++
                    } finally {
                        crop.recycle()
                    }
                }
                found
            } finally {
                bitmap.recycle()
            }
        }

        return savedCrops
    }

    /**
     * Runs the detector on the live preview a couple of times per second and draws a numbered box
     * around every watch it sees, so you can check framing before and during a session. The
     * numbers match the left-to-right dial_index used in the saved files.
     */
    private fun startLiveDetection() {
        lifecycleScope.launch {
            while (isActive) {
                delay(LIVE_INTERVAL_MS)
                if (dimmed) continue // screen is dimmed: skip the live detection work
                val detector = watchDetector ?: continue
                if (imageCapture == null || previewView.width == 0) continue
                val frame = previewView.bitmap ?: continue
                if (!detectorMutex.tryLock()) {
                    frame.recycle()
                    continue
                }
                val viewW = previewView.width.toFloat()
                val viewH = previewView.height.toFloat()
                val boxes = try {
                    withContext(Dispatchers.IO) {
                        val longSide = maxOf(frame.width, frame.height)
                        val small = if (longSide > LIVE_MAX_SIDE) {
                            val f = LIVE_MAX_SIDE.toFloat() / longSide
                            Bitmap.createScaledBitmap(frame, (frame.width * f).toInt(), (frame.height * f).toInt(), true)
                        } else frame
                        try {
                            val sx = viewW / small.width
                            val sy = viewH / small.height
                            detector.detect(small)
                                .sortedWith(compareBy({ it.box.centerX() }, { it.box.centerY() }))
                                .mapIndexed { i, d ->
                                    val reading = clockReader?.let { r ->
                                        cropWatch(small, d.box)?.let { (crop, _) ->
                                            try { r.read(crop) } finally { crop.recycle() }
                                        }
                                    }
                                    LiveBox(RectF(d.box.left * sx, d.box.top * sy, d.box.right * sx, d.box.bottom * sy), i + 1, d.score, reading)
                                }
                        } finally {
                            if (small !== frame) small.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Live detection failed: ${e.message}")
                    emptyList()
                } finally {
                    detectorMutex.unlock()
                    frame.recycle()
                }
                liveOverlay.setBoxes(boxes)
                liveStatusText.text = if (boxes.isEmpty()) getString(R.string.live_no_watches)
                else getString(R.string.live_watches_in_view, boxes.size)
            }
        }
    }

    private fun cropWatch(bitmap: Bitmap, box: RectF): Pair<Bitmap, android.graphics.Rect>? {
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

        return Bitmap.createBitmap(bitmap, left, top, width, height) to
            android.graphics.Rect(left, top, right, bottom)
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    private fun formatIso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(millis))

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
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            val local = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES,
                    bytes, sessionTimestamp, fileName, "image/jpeg"
                )
            } else {
                saveLegacy(bytes, sessionTimestamp, fileName, "image/jpeg")
            }
            enqueueUpload(sessionTimestamp, fileName, bytes, local)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $fileName", e)
        }
    }

    /**
     * Writes [annotation] as [fileName] (`<image base name>.json`). MediaStore only accepts
     * non-media files under `Documents/` or `Download/` on Android 10+, so there the JSON is
     * stored in `Documents/WatchReaderTrainingData/<session>/`; on older versions it is written
     * right next to the image in `Pictures/WatchReaderTrainingData/<session>/`.
     */
    private fun saveAnnotation(annotation: WatchAnnotation, sessionTimestamp: String, fileName: String) {
        try {
            val bytes = annotation.toJson().toString(2).toByteArray(Charsets.UTF_8)
            val local = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(
                    MediaStore.Files.getContentUri("external"), Environment.DIRECTORY_DOCUMENTS,
                    bytes, sessionTimestamp, fileName, "application/json"
                )
            } else {
                saveLegacy(bytes, sessionTimestamp, fileName, "application/json")
            }
            enqueueUpload(sessionTimestamp, fileName, bytes, local)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $fileName", e)
        }
    }

    /** [local] is the saved copy on the phone (content Uri or file path); it is deleted once uploaded. */
    private fun enqueueUpload(sessionTimestamp: String, fileName: String, bytes: ByteArray, local: String?) {
        if (!UploadScheduler.isEnabled(this)) return
        try {
            UploadScheduler.enqueue(this, sessionTimestamp, fileName, bytes, local)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue upload of $fileName", e)
        }
    }

    private fun saveToMediaStore(
        collection: android.net.Uri, baseDir: String, bytes: ByteArray,
        sessionTimestamp: String, fileName: String, mimeType: String
    ): String {
        val relativePath = "$baseDir/$TRAINING_DATA_DIR/$sessionTimestamp"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry for $fileName")

        resolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
        } ?: throw IOException("Failed to open output stream for $fileName")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    private fun saveLegacy(bytes: ByteArray, sessionTimestamp: String, fileName: String, mimeType: String): String {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            ?: throw IOException("Public Pictures directory is unavailable (external storage not mounted)")
        val dir = File(picturesDir, "$TRAINING_DATA_DIR/$sessionTimestamp")
        dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { out -> out.write(bytes) }
        // Make the new file immediately visible to gallery apps/file managers.
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mimeType), null)
        return file.absolutePath
    }

    /** Shows pending/done/failed counts of queued FTP/SFTP uploads. */
    private fun observeUploads() {
        lifecycleScope.launch {
            WorkManager.getInstance(applicationContext)
                .getWorkInfosByTagFlow(UploadWorker.WORK_TAG)
                .collect { infos ->
                    if (infos.isEmpty() && !UploadScheduler.isEnabled(this@TrainingCaptureActivity)) {
                        uploadStatusText.text = getString(R.string.upload_status_disabled)
                        return@collect
                    }
                    val done = infos.count { it.state == WorkInfo.State.SUCCEEDED }
                    val failed = infos.count { it.state == WorkInfo.State.FAILED }
                    val pending = infos.count { !it.state.isFinished }
                    val lastError = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
                        ?.outputData?.getString(UploadWorker.KEY_ERROR)
                    uploadStatusText.text = getString(
                        R.string.upload_status, pending, done, failed,
                        if (lastError != null) " ($lastError)" else ""
                    )
                }
        }
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
        // Guard against double starts (e.g. rapid taps) which would otherwise hit EADDRINUSE.
        if (fileServer != null) return
        try {
            val server = FileServer.startWithFallback(applicationContext)
            fileServer = server
            val port = server.listeningPort
            val ip = getLocalIpAddress() ?: getString(R.string.server_ip_unknown)
            val url = "http://$ip:$port/?token=${server.accessToken}"
            var text = getString(R.string.server_status_running, url)
            if (port != FileServer.DEFAULT_PORT) {
                text += "\n" + getString(R.string.server_port_fallback_note, FileServer.DEFAULT_PORT, port)
            }
            serverStatusText.text = text
            serverStatusText.visibility = View.VISIBLE
            serverToggleButton.text = getString(R.string.action_stop_server)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start file server", e)
            fileServer = null
            serverStatusText.text = getString(R.string.error_template, e.message)
        }
    }

    private fun stopFileServer() {
        try {
            fileServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error while stopping file server", e)
        }
        fileServer = null
        serverStatusText.text = getString(R.string.server_status_stopped)
        serverStatusText.visibility = View.GONE
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

    override fun onStop() {
        super.onStop()
        // Release the listening socket as soon as the screen goes away so it can't linger and
        // cause EADDRINUSE on the next start.
        if (fileServer != null) stopFileServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        idleHandler.removeCallbacks(dimRunnable)
        captureLoopJob?.cancel()
        cameraControl = null
        imageCapture = null
        if (fileServer != null) stopFileServer()
        cameraExecutor.shutdown()
        watchDetector?.close()
        clockReader?.close()
    }
}
