package com.yourname.watchreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** A single detection in the coordinate space of the bitmap passed to the detector. */
data class WatchDetection(val box: RectF, val score: Float, val category: String)

/**
 * Thin wrapper around the MediaPipe [ObjectDetector] used to find watch faces in a bitmap.
 *
 * The bundled model is a COCO EfficientDet-Lite style detector with a small (~320x320)
 * input, so for multi-watch use ([maxResults] > 1) the frame is also scanned in overlapping
 * tiles and the results merged with NMS; otherwise small watches shrink to a few pixels.
 */
class WatchDetector(
    context: Context,
    private val maxResults: Int = 1,
    scoreThreshold: Float = 0.3f
) {

    private val objectDetector: ObjectDetector = run {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()

        // Only keep the COCO "clock" class so other classes don't consume result slots.
        // Per-pass cap is higher than maxResults because tiles/NMS merge results afterwards.
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(scoreThreshold) // Lower for older cameras like the Zebra
            .setMaxResults(maxOf(maxResults, MIN_PASS_RESULTS))
            .setCategoryAllowlist(listOf(WATCH_CATEGORY))
            .build()

        ObjectDetector.createFromOptions(context, options)
    }

    /**
     * Runs detection on [bitmap] and returns the bounding boxes of every detected watch,
     * in the original bitmap's coordinate space. The list is empty when no watch is found.
     */
    fun detectWatches(bitmap: Bitmap): List<RectF> = detect(bitmap).map { it.box }

    /** Like [detectWatches] but also returns score and category, sorted by score. */
    fun detect(bitmap: Bitmap): List<WatchDetection> {
        val raw = detectPass(bitmap, 0f, 0f).toMutableList()

        if (maxResults > 1) {
            val tileW = (bitmap.width * TILE_FRACTION).toInt()
            val tileH = (bitmap.height * TILE_FRACTION).toInt()
            if (tileW > 0 && tileH > 0) {
                for (x in listOf(0, bitmap.width - tileW)) {
                    for (y in listOf(0, bitmap.height - tileH)) {
                        val tile = Bitmap.createBitmap(bitmap, x, y, tileW, tileH)
                        try {
                            raw += detectPass(tile, x.toFloat(), y.toFloat())
                        } finally {
                            if (tile !== bitmap) tile.recycle()
                        }
                    }
                }
            }
        }

        raw.forEach { Log.d(TAG, "raw: ${it.category} score=${it.score} box=${it.box}") }
        val merged = nms(raw, NMS_IOU_THRESHOLD).take(maxResults)
        Log.d(TAG, "raw=${raw.size} merged=${merged.size}")
        return merged
    }

    private fun detectPass(bitmap: Bitmap, offsetX: Float, offsetY: Float): List<WatchDetection> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val results = objectDetector.detect(mpImage)
        return results.detections().mapNotNull { d ->
            val category = d.categories().firstOrNull() ?: return@mapNotNull null
            val b = d.boundingBox()
            WatchDetection(
                RectF(b.left + offsetX, b.top + offsetY, b.right + offsetX, b.bottom + offsetY),
                category.score(),
                category.categoryName()
            )
        }
    }

    fun close() {
        objectDetector.close()
    }

    companion object {
        private const val TAG = "WatchDetector"
        private const val MODEL_ASSET_PATH = "clock_detector.tflite"
        private const val WATCH_CATEGORY = "clock"
        private const val MIN_PASS_RESULTS = 10
        private const val TILE_FRACTION = 0.6f
        private const val NMS_IOU_THRESHOLD = 0.5f

        /** Greedy IoU-based non-maximum suppression; result is sorted by descending score. */
        internal fun nms(detections: List<WatchDetection>, iouThreshold: Float): List<WatchDetection> {
            val kept = mutableListOf<WatchDetection>()
            for (d in detections.sortedByDescending { it.score }) {
                if (kept.none { iou(it.box, d.box) > iouThreshold }) kept += d
            }
            return kept
        }

        private fun iou(a: RectF, b: RectF): Float {
            val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (iw <= 0f || ih <= 0f) return 0f
            val inter = iw * ih
            val union = a.width() * a.height() + b.width() * b.height() - inter
            return if (union <= 0f) 0f else inter / union
        }
    }
}
