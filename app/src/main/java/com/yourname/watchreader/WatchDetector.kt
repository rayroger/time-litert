package com.yourname.watchreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Thin wrapper around the MediaPipe [ObjectDetector] used to find watch faces in a bitmap.
 *
 * Supports returning more than one detection so that multiple watches present in the
 * same frame can be detected, annotated and cropped independently.
 */
class WatchDetector(
    context: Context,
    maxResults: Int = 1,
    scoreThreshold: Float = 0.3f
) {

    private val objectDetector: ObjectDetector = run {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(scoreThreshold) // Lower for older cameras like the Zebra
            .setMaxResults(maxResults)
            .build()

        ObjectDetector.createFromOptions(context, options)
    }

    /**
     * Runs detection on [bitmap] and returns the bounding boxes of every detected watch,
     * in the original bitmap's coordinate space. The list is empty when no watch is found.
     */
    fun detectWatches(bitmap: Bitmap): List<RectF> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val results = objectDetector.detect(mpImage)
        return results.detections().map { it.boundingBox() }
    }

    fun close() {
        objectDetector.close()
    }

    companion object {
        private const val MODEL_ASSET_PATH = "clock_detector.tflite"
    }
}
