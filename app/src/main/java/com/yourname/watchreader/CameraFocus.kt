package com.yourname.watchreader

import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraFocus"
private const val FOCUS_TIMEOUT_MILLIS = 6_000L

/**
 * Starts center-point focus and metering, and waits for CameraX to finish the operation.
 *
 * View access and focus setup are dispatched to the main thread.
 * Returns false if the preview has no laid-out size, focus is unsuccessful, or the operation
 * times out or fails. Caller cancellation is propagated.
 */
suspend fun CameraControl.focusAndMeterAtCenter(previewView: PreviewView): Boolean =
    withContext(Dispatchers.Main.immediate) {
        if (previewView.width == 0 || previewView.height == 0) return@withContext false

        try {
            val focusSucceeded = withTimeoutOrNull(FOCUS_TIMEOUT_MILLIS) {
                val point = previewView.meteringPointFactory.createPoint(
                    previewView.width / 2f,
                    previewView.height / 2f
                )
                val future = startFocusAndMetering(FocusMeteringAction.Builder(point).build())

                suspendCancellableCoroutine { continuation ->
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

            if (focusSucceeded != true) {
                Log.w(TAG, if (focusSucceeded == null) "Autofocus timed out" else "Autofocus was unsuccessful")
            }
            focusSucceeded == true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "Autofocus failed", exception)
            false
        }
    }
