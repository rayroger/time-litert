package com.yourname.watchreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var resultText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var overlayToggle: SwitchCompat
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
	private var objectDetector: ObjectDetector? = null

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
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("clock_detector.tflite")
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(0.3f) // Lower for older cameras like the Zebra
            .setMaxResults(1)
            .build()

        objectDetector = ObjectDetector.createFromOptions(this, options)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        overlayToggle = findViewById(R.id.overlayToggle)
        val readButton = findViewById<Button>(R.id.readButton)

        cameraExecutor = Executors.newSingleThreadExecutor()

        readButton.setOnClickListener {
            captureImage()
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

            imageCapture = ImageCapture.Builder().build()

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

    private fun captureImage() {
        val imageCapture = imageCapture ?: return

        resultText.text = getString(R.string.status_thinking)

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    readTimeLocally(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    resultText.text = getString(R.string.error_template, exception.message)
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        // ImageCapture produces JPEG format, so we need to decode from the JPEG buffer
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        
        // Rotate bitmap if needed
        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        
        return bitmap
    }
	
	private fun readTimeLocally(bitmap: Bitmap) {
        val detector = objectDetector
        if (detector == null) {
            resultText.text = "Object detector not initialized"
            return
        }
        
		val mpImage = BitmapImageBuilder(bitmap).build()
		val results = detector.detect(mpImage)

		if (results.detections().isNotEmpty()) {
			val detection = results.detections()[0]
			val box = detection.boundingBox()
			
			// Scale coordinates from captured image to preview dimensions
			val scaleX = previewView.width.toFloat() / bitmap.width.toFloat()
			val scaleY = previewView.height.toFloat() / bitmap.height.toFloat()
			
			val scaledBox = RectF(
			    box.left * scaleX,
			    box.top * scaleY,
			    box.right * scaleX,
			    box.bottom * scaleY
			)
			
			// Update overlay with detected box (already on UI thread)
			overlayView.setDetectionBox(scaledBox)
			
			resultText.text = "Watch detected, analyzing time..."
			
			// Extract watch region and read time
			val left = box.left.toInt().coerceAtLeast(0)
			val top = box.top.toInt().coerceAtLeast(0)
			val width = box.width().toInt().coerceAtMost(bitmap.width - left)
			val height = box.height().toInt().coerceAtMost(bitmap.height - top)
			
			val watchRegion = Bitmap.createBitmap(
			    bitmap,
			    left,
			    top,
			    width,
			    height
			)
			
			readTimeFromWatch(watchRegion)
		} else {
			// Clear overlay (already on UI thread)
			overlayView.setDetectionBox(null)
			resultText.text = "No watch found. Adjust lighting."
    }
}

    private fun readTimeFromWatch(bitmap: Bitmap) {
        // TODO: Implement time recognition using an appropriate method
        // Options include:
        // 1. Custom TensorFlow Lite model trained specifically for clock hand detection
        // 2. Computer vision techniques (Hough transform for line detection)
        // 3. OCR for digital watches
        // 4. Integration with a specialized time-reading API
        
        try {
            // Placeholder: For now, indicate that watch was detected but time reading
            // requires a proper clock hand detection model
            resultText.text = "Watch detected! Time recognition requires a specialized clock hand detection model."
            
            // When implementing, the approach should:
            // - Detect clock hands (hour and minute hands) in the watch region
            // - Calculate angles of each hand relative to 12 o'clock position
            // - Convert angles to time in HH:mm format
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading time from watch", e)
            resultText.text = getString(R.string.error_template, e.message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
