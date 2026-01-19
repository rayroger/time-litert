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
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.framework.image.BitmapImageBuilder
import android.graphics.PointF
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
    // Initialize MediaPipe Landmarker instead of ModelClient
    private var handLandmarker: HandLandmarker? = null
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
    
    private fun setupLocalModel() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("watch_hands.tflite") // You will add this file to /assets
            .build()
            
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .build()
            
        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }
    
    // Math logic to calculate time from detected coordinates
    private fun calculateTime(center: PointF, hrTip: PointF, minTip: PointF): String {
        val hrAngle = Math.toDegrees(Math.atan2((hrTip.y - center.y).toDouble(), (hrTip.x - center.x).toDouble())) + 90
        val minAngle = Math.toDegrees(Math.atan2((minTip.y - center.y).toDouble(), (minTip.x - center.x).toDouble())) + 90
        
        val hour = ((hrAngle.plus(360) % 360) / 30).toInt().let { if (it == 0) 12 else if (it > 12) it - 12 else it }
        val minute = ((minAngle.plus(360) % 360) / 6).toInt()
        
        return String.format("%02d:%02d", hour, minute)
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
		
		// Initialize MediaPipe detectors here
	    try {
	        setupDetector()  // Initialize object detector for watch detection
	        setupLocalModel()  // Initialize hand landmarker for time calculation
	    } catch (e: Exception) {
	        Log.e(TAG, "Failed to initialize detectors", e)
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
			
			// Update overlay with detected box
			runOnUiThread {
			    overlayView.setDetectionBox(scaledBox)
			}
			
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
			runOnUiThread {
			    overlayView.setDetectionBox(null)
			}
			resultText.text = "No watch found. Adjust lighting."
    }
}

    private fun readTimeFromWatch(bitmap: Bitmap) {
        val landmarker = handLandmarker
        if (landmarker == null) {
            resultText.text = "Hand landmarker not initialized"
            return
        }
        
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)
            
            if (result.landmarks().isNotEmpty()) {
                val landmarks = result.landmarks()[0]
                
                // Extract key points for clock hands
                // Note: HandLandmarker detects hand landmarks, not clock hands.
                // The model at watch_hands.tflite should be specifically trained for clock hands.
                // landmarks[0] = clock center, landmarks[1] = hour hand tip, landmarks[2] = minute hand tip
                // Adjust indices based on actual model output
                if (landmarks.size >= 3) {
                    val center = PointF(
                        landmarks[0].x() * bitmap.width,
                        landmarks[0].y() * bitmap.height
                    )
                    val hourHandTip = PointF(
                        landmarks[1].x() * bitmap.width,
                        landmarks[1].y() * bitmap.height
                    )
                    val minuteHandTip = PointF(
                        landmarks[2].x() * bitmap.width,
                        landmarks[2].y() * bitmap.height
                    )
                    
                    val time = calculateTime(center, hourHandTip, minuteHandTip)
                    resultText.text = getString(R.string.time_template, time)
                } else {
                    resultText.text = "Could not detect clock hands clearly"
                }
            } else {
                // Fallback: Use a simple approach based on image analysis
                // This is a placeholder for more sophisticated analysis
                resultText.text = "Clock hands not detected. Try a clearer image."
            }
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
