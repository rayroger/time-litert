# Watch Reader AI (LiteRt)

A local, private Android app that uses on-device Generative AI to read the time from analog watches.

## 🚀 Features
- **100% Offline:** No data leaves the device.
- **Recognize Watches Automatically:** New functionality now enables the app to detect and recognize watches within a captured image, and identify their coordinates, including scenes with **multiple watches at once**.
- **Powered by Gemini Nano:**  ML Kit   API.

## 📂 Training Data Capture Mode

`TrainingCaptureActivity` periodically captures photos, detects every watch in frame, and saves:
- `capture_<n>_annotated.jpg` — the full photo with a green bounding box per detected watch.
- `capture_<n>_watch_<m>.jpg` — a cropped image for each detected watch.

Captures are written to the **public, externally-visible** media collection
`Pictures/WatchReaderTrainingData/<session-timestamp>/`, so they:
- Show up in a file manager or gallery app (no `adb`/root required).
- Survive uninstalling the app.

On Android 10+ this uses the `MediaStore` API (no extra permission needed). On Android 9 and
below it writes directly to the legacy public Pictures directory and requests the
`WRITE_EXTERNAL_STORAGE` runtime permission (declared with `maxSdkVersion="28"` in the manifest).

### Uploading captures to a PC

Training Data Capture mode includes a **"Start Upload Server"** button that starts a small
embedded HTTP server (via [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)) on port `8080`.
While running, the screen shows the device's local Wi-Fi IP address, e.g.
`http://192.168.1.42:8080`. Open that address from any PC browser on the same Wi-Fi network (or
`curl`/`wget` it) to see a list of all saved captures and download them individually — no `adb
pull` needed. Tap **"Stop Upload Server"** when you're done to free the port.

## 📱 Requirements


## 🛠️ Setup (2026-ready)
The snippets below are the must-have files for an empty Views/Compose Activity project.

### 1) `app/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.yourname.watchreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourname.watchreader"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    // ML Kit GenAI Prompt API for Gemini Nano Access
    implementation("com.google.mlkit:genai-prompt:1.0.0-alpha1")

    // CameraX for camera capture
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // Standard UI and Lifecycle
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
```
> Note: ML Kit GenAI Prompt API is currently published as `1.0.0-alpha1`. Update to the stable release when available.

### 2) `AndroidManifest.xml`
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />

    <application
        android:label="Watch Reader AI"
        android:theme="@style/Theme.Material3.DayNight">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

### 3) `MainActivity.kt`


    private fun detectWatchAndReadTime(bitmap: Bitmap) {
        resultText.text = "Watch detected with bounds." 
                    overlayDraw (detected area UI overdue Call
Meanwhile DrawOverlay
```
