package com.yourname.watchreader

import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun CameraControl.focusAndMeterAtCenter(previewView: PreviewView): Boolean {
    if (previewView.width == 0 || previewView.height == 0) return false

    val point = previewView.meteringPointFactory.createPoint(
        previewView.width / 2f,
        previewView.height / 2f
    )
    val future = startFocusAndMetering(FocusMeteringAction.Builder(point).build())

    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { future.cancel(true) }
        future.addListener({
            try {
                continuation.resume(future.get().isFocusSuccessful)
            } catch (exception: ExecutionException) {
                continuation.resumeWithException(exception.cause ?: exception)
            } catch (exception: Exception) {
                continuation.resumeWithException(exception)
            }
        }, Executor { it.run() })
    }
}
