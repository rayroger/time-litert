package com.yourname.watchreader

import android.graphics.RectF
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Axis-aligned box as `[left, top, right, bottom]` in pixels. */
data class BoxPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    constructor(r: RectF) : this(r.left, r.top, r.right, r.bottom)

    fun toJson(): JSONArray = JSONArray().put(left.toDouble()).put(top.toDouble()).put(right.toDouble()).put(bottom.toDouble())
}

/** An (x, y) point in crop-image pixel coordinates. */
data class PointPx(val x: Float, val y: Float) {
    fun toJson(): JSONArray = JSONArray().put(x.toDouble()).put(y.toDouble())
}

/**
 * Hand readings/keypoints for one dial. All values are nullable because the current pipeline only
 * detects the dial; they are filled in when a hand model or a human annotator provides them.
 * Angles are degrees clockwise from 12 o'clock.
 */
data class HandAnnotation(
    val hour: Int? = null,
    val minute: Int? = null,
    val second: Int? = null,
    val hourAngleDeg: Float? = null,
    val minuteAngleDeg: Float? = null,
    val secondAngleDeg: Float? = null,
    val center: PointPx? = null,
    val hourTip: PointPx? = null,
    val minuteTip: PointPx? = null,
    val secondTip: PointPx? = null,
    val confidence: Float? = null
)

/** Per-image annotation written as `<image base name>.json` next to each dial crop. */
data class WatchAnnotation(
    val imageFilename: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val captureTimestampMillis: Long,
    val captureTimestampIso: String,
    val session: String,
    val captureIndex: Int,
    val dialIndex: Int,
    val sourceImageFilename: String,
    val sourceImageWidth: Int,
    val sourceImageHeight: Int,
    val dialBoxSource: BoxPx,
    val dialBoxCrop: BoxPx,
    val detectionScore: Float,
    val detectionCategory: String,
    val modelName: String,
    val modelVersion: String,
    val hands: HandAnnotation = HandAnnotation(),
    /** `auto` for machine-generated labels, `manual` once a human has set/corrected them. */
    val labelSource: String = LABEL_SOURCE_AUTO,
    val verified: Boolean = false
) {
    fun toJson(): JSONObject {
        fun opt(v: Any?): Any = v ?: JSONObject.NULL
        val h = hands
        return JSONObject()
            .put("format_version", FORMAT_VERSION)
            .put("image", JSONObject()
                .put("filename", imageFilename)
                .put("width", imageWidth)
                .put("height", imageHeight))
            .put("capture", JSONObject()
                .put("timestamp_millis", captureTimestampMillis)
                .put("timestamp_iso", captureTimestampIso)
                .put("session", session)
                .put("capture_index", captureIndex)
                .put("dial_index", dialIndex))
            .put("source_image", JSONObject()
                .put("filename", sourceImageFilename)
                .put("width", sourceImageWidth)
                .put("height", sourceImageHeight))
            .put("dial_bbox", JSONObject()
                .put("format", "xyxy")
                .put("source", dialBoxSource.toJson())
                .put("crop", dialBoxCrop.toJson()))
            .put("hands", JSONObject()
                .put("hour", opt(h.hour))
                .put("minute", opt(h.minute))
                .put("second", opt(h.second))
                .put("hour_angle_deg", opt(h.hourAngleDeg?.toDouble()))
                .put("minute_angle_deg", opt(h.minuteAngleDeg?.toDouble()))
                .put("second_angle_deg", opt(h.secondAngleDeg?.toDouble()))
                .put("confidence", opt(h.confidence?.toDouble())))
            .put("keypoints", JSONObject()
                .put("coordinate_space", "crop")
                .put("dial_center", opt(h.center?.toJson()))
                .put("hour_tip", opt(h.hourTip?.toJson()))
                .put("minute_tip", opt(h.minuteTip?.toJson()))
                .put("second_tip", opt(h.secondTip?.toJson())))
            .put("model", JSONObject()
                .put("name", modelName)
                .put("version", modelVersion)
                .put("detection_category", detectionCategory)
                .put("detection_confidence", detectionScore.toDouble()))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android_sdk", Build.VERSION.SDK_INT))
            .put("label_source", labelSource)
            .put("verified", verified)
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val LABEL_SOURCE_AUTO = "auto"
        const val LABEL_SOURCE_MANUAL = "manual"
    }
}
