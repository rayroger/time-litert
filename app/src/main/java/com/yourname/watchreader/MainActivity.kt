package com.yourname.watchreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var resultText: TextView
    private lateinit var readButton: Button
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var overlayToggle: SwitchCompat
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null
    private var captureInProgress = false
    private var watchDetector: WatchDetector? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupDetector() {
        watchDetector = WatchDetector(this, maxResults = WatchDetector.MAX_DETECTIONS_PER_CAPTURE)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        overlayToggle = findViewById(R.id.overlayToggle)
        readButton = findViewById(R.id.readButton)
        val trainingModeButton = findViewById<Button>(R.id.trainingModeButton)

        cameraExecutor = Executors.newSingleThreadExecutor()

        readButton.setOnClickListener {
            captureImage()
        }

        trainingModeButton.setOnClickListener {
            startActivity(Intent(this, TrainingCaptureActivity::class.java))
        }
        
        overlayToggle.setOnCheckedChangeListener { _, isChecked ->
            overlayView.isOverlayEnabled = isChecked
            overlayView.invalidate()
        }
        
        // Initialize MediaPipe detector
        try {
            setupDetector()  // Initialize object detector for watch detection
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector", e)
            resultText.text = "Initialization failed: ${e.message}"
        }
        
        // Request camera permission
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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

            val capture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
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

    private fun captureImage() {
        if (captureInProgress) return
        val imageCapture = imageCapture ?: return

        captureInProgress = true
        readButton.isEnabled = false
        resultText.text = getString(R.string.status_thinking)

        lifecycleScope.launch {
            try {
                cameraControl?.focusAndMeterAtCenter(previewView)
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(this@MainActivity),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bitmap = imageProxyToBitmap(image)
                                readTimeLocally(bitmap)
                            } catch (exception: Exception) {
                                Log.e(TAG, "Failed to process captured image", exception)
                                resultText.text = getString(R.string.error_template, exception.message)
                            } finally {
                                image.close()
                                finishCapture()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            finishCapture()
                            Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                            resultText.text = getString(R.string.error_template, exception.message)
                        }
                    }
                )
            } catch (exception: CancellationException) {
                finishCapture()
                throw exception
            } catch (exception: Exception) {
                finishCapture()
                Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                resultText.text = getString(R.string.error_template, exception.message)
            }
        }
    }

    private fun finishCapture() {
        captureInProgress = false
        readButton.isEnabled = true
    }

    private fun readTimeLocally(bitmap: Bitmap) {
        val detector = watchDetector
        if (detector == null) {
            resultText.text = "Object detector not initialized"
            return
        }
        
        // Detect every watch present in the frame, not just the first one.
        val detections = detector.detectWatches(bitmap)

        if (detections.isNotEmpty()) {
            // Scale coordinates from captured image to preview dimensions
            val scaleX = previewView.width.toFloat() / bitmap.width.toFloat()
            val scaleY = previewView.height.toFloat() / bitmap.height.toFloat()

            val scaledBoxes = detections.map { box ->
                RectF(
                    box.left * scaleX,
                    box.top * scaleY,
                    box.right * scaleX,
                    box.bottom * scaleY
                )
            }

            // Update overlay with all detected boxes (already on UI thread)
            overlayView.setDetectionBoxes(scaledBoxes)

            // Extract each watch region and read time for every detection.
            val watchLines = detections.mapIndexed { index, box ->
                val left = box.left.toInt().coerceAtLeast(0)
                val top = box.top.toInt().coerceAtLeast(0)
                val width = box.width().toInt().coerceAtMost(bitmap.width - left)
                val height = box.height().toInt().coerceAtMost(bitmap.height - top)

                // Validate dimensions before creating bitmap
                if (width > 0 && height > 0) {
                    val watchRegion = Bitmap.createBitmap(bitmap, left, top, width, height)
                    readTimeFromWatch(watchRegion, index + 1)
                } else {
                    getString(R.string.watch_region_too_small, index + 1)
                }
            }

            resultText.text = getString(R.string.watches_found_template, detections.size) +
                "\n" + watchLines.joinToString("\n")
        } else {
            // Clear overlay (already on UI thread)
            overlayView.setDetectionBoxes(emptyList())
            resultText.text = getString(R.string.status_no_watch)
        }
    }

    /**
     * Returns a one-line status for the watch found in [bitmap], identified by [index]
     * (1-based, matching the order it was detected in).
     */
    private fun readTimeFromWatch(bitmap: Bitmap, index: Int): String {
        // TODO: Implement time recognition using an appropriate method
        // Options include:
        // 1. Custom TensorFlow Lite model trained specifically for clock hand detection
        // 2. Computer vision techniques (Hough transform for line detection)
        // 3. OCR for digital watches
        // 4. Integration with a specialized time-reading API
        
        return try {
            // Placeholder: For now, indicate that watch was detected but time reading
            // requires a proper clock hand detection model
            getString(R.string.watch_time_placeholder, index)
            
            // When implementing, the approach should:
            // - Detect clock hands (hour and minute hands) in the watch region
            // - Calculate angles of each hand relative to 12 o'clock position
            // - Convert angles to time in HH:mm format
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading time from watch $index", e)
            getString(R.string.error_template, e.message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        watchDetector?.close()
    }
}
