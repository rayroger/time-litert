package com.yourname.watchreader

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Reads the time from one dial crop with the trained model (assets/clock_reader.tflite, built by
 * training/train.py). Input: 224x224 RGB, raw 0-255 floats (the model rescales itself). Output:
 * [sin, cos] of the hour-hand angle and [sin, cos] of the minute-hand angle.
 * [create] returns null when the model file is not bundled, so the app still works without it.
 */
class ClockReader private constructor(private val interpreter: Interpreter) {

    private val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)

    /** "H:MM" for a dial crop, or null if inference fails. Not thread-safe: call from one thread. */
    @Synchronized
    fun read(crop: Bitmap): String? = try {
        val scaled = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== crop) scaled.recycle()
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF).toFloat())
            input.putFloat(((p shr 8) and 0xFF).toFloat())
            input.putFloat((p and 0xFF).toFloat())
        }
        input.rewind()
        val out = Array(1) { FloatArray(4) }
        interpreter.run(input, out)
        decode(out[0])
    } catch (e: Exception) {
        Log.w(TAG, "clock inference failed: ${e.message}")
        null
    }

    fun close() = interpreter.close()

    companion object {
        private const val TAG = "ClockReader"
        private const val ASSET = "clock_reader.tflite"
        private const val SIZE = 224

        fun create(context: Context): ClockReader? = try {
            val fd = context.assets.openFd(ASSET)
            val model = fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            ClockReader(Interpreter(model, Interpreter.Options().setNumThreads(2)))
        } catch (e: Exception) {
            Log.i(TAG, "clock model not available: ${e.message}")
            null
        }

        /** Same decoding as training/common.targets_to_time: minute from the minute hand, hour from the hour hand minus the minute share. */
        fun decode(t: FloatArray): String {
            fun deg(s: Float, c: Float) = (Math.toDegrees(atan2(s.toDouble(), c.toDouble())) + 360.0) % 360.0
            val minuteF = deg(t[2], t[3]) / 6.0
            val hourF = deg(t[0], t[1]) / 30.0
            val minute = minuteF.roundToInt() % 60
            var hour = ((hourF - minuteF / 60.0).roundToInt() % 12 + 12) % 12
            if (hour == 0) hour = 12
            return "%d:%02d".format(hour, minute)
        }
    }
}
