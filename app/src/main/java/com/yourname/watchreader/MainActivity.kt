package com.yourname.watchreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.core.BaseOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var resultText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    // Initialize MediaPipe Landmarker instead of ModelClient
    private lateinit var handLandmarker: HandLandmarker
	private lateinit var objectDetector: ObjectDetector

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val generativeModel: GenerativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
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
    private fun calculateTime(center: Point, hrTip: Point, minTip: Point): String {
        val hrAngle = Math.toDegrees(Math.atan2((hrTip.y - center.y).toDouble(), (hrTip.x - center.x).toDouble())) + 90
        val minAngle = Math.toDegrees(Math.atan2((minTip.y - center.y).toDouble(), (minTip.x - center.x).toDouble())) + 90
        
        val hour = ((hrAngle.plus(360) % 360) / 30).toInt().let { if (it == 0) 12 else it }
        val minute = ((minAngle.plus(360) % 360) / 6).toInt()
        
        return String.format("%02d:%02d", hour, minute)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)
        previewView = findViewById(R.id.previewView)
        val readButton = findViewById<Button>(R.id.readButton)

        cameraExecutor = Executors.newSingleThreadExecutor()

        readButton.setOnClickListener {
            captureImage()
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
		val mpImage = BitmapImageBuilder(bitmap).build()
		val results = objectDetector.detect(mpImage)

		if (results.detections().isNotEmpty()) {
			val detection = results.detections()[0]
			val box = detection.boundingBox()
			resultText.text = "Watch detected at: ${box.left}, ${box.top}"
			// Next step: apply hand-detection logic within this box
		} else {
			resultText.text = "No watch found. Adjust lighting."
    }
}

    private fun readTimeFromWatch(bitmap: Bitmap) {
        val prompt = "Analyze this analog watch. What time is shown? Be precise. Return only HH:mm."

        lifecycleScope.launch {
            try {
                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
                resultText.text = getString(R.string.time_template, response.text ?: "Unable to read")
            } catch (e: Exception) {
                resultText.text = getString(R.string.error_template, e.localizedMessage ?: "Unknown error")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
