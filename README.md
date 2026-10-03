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
While running, the screen shows the device's local Wi-Fi IP address and a one-time access
token appended as a URL, e.g. `http://192.168.1.42:8080/?token=<random-uuid>`. Open that exact
address from any PC browser on the same Wi-Fi network (or `curl`/`wget` it) to see a list of all
saved captures and download them individually — no `adb pull` needed.

The token is required because the server has no other authentication, and is regenerated every
time the server is (re)started. The very first request must include `?token=...`; after that the
server sets a session cookie so you can keep browsing/downloading without repeating the token in
every link (keeping it out of browser history/Referer headers for subsequent navigation). Only
share the printed URL with people/PCs you trust on your Wi-Fi network. Tap **"Stop Upload
Server"** when you're done to free the port.

### Annotation metadata (for NN training)

For every saved dial crop `capture_<n>_watch_<m>.jpg` an annotation file with the same base name,
`capture_<n>_watch_<m>.json`, is written. On Android 10+ MediaStore only accepts non-media files
under `Documents/`, so the JSON lands in `Documents/WatchReaderTrainingData/<session>/`; on
Android 9 and below it is written next to the image in `Pictures/WatchReaderTrainingData/<session>/`.

```json
{
  "format_version": 1,
  "image":   {"filename": "capture_1_watch_1.jpg", "width": 412, "height": 398},
  "capture": {"timestamp_millis": 1700000000000, "timestamp_iso": "2023-11-14T22:13:20.000Z",
              "session": "20231114_221320", "capture_index": 1, "dial_index": 1},
  "source_image": {"filename": "capture_1_annotated.jpg", "width": 4000, "height": 3000},
  "dial_bbox": {"format": "xyxy", "source": [l, t, r, b], "crop": [0, 0, w, h]},
  "hands": {"hour": null, "minute": null, "second": null,
            "hour_angle_deg": null, "minute_angle_deg": null, "second_angle_deg": null,
            "confidence": null},
  "keypoints": {"coordinate_space": "crop", "dial_center": null,
                "hour_tip": null, "minute_tip": null, "second_tip": null},
  "model":  {"name": "clock_detector.tflite", "version": "<app versionName>",
             "detection_category": "clock", "detection_confidence": 0.87},
  "device": {"manufacturer": "...", "model": "...", "android_sdk": 34},
  "label_source": "auto",
  "verified": false
}
```

- `dial_bbox` is `[left, top, right, bottom]` in pixels, in source-photo and crop coordinates.
- Hand values/angles (degrees clockwise from 12) and keypoints are `null` until a hand model or a
  human fills them in (the current detector only locates the dial).
- `label_source` is `auto` for machine labels; set it to `manual` and `verified` to `true` after
  correcting a file by hand.

### Local server port handling

The upload server prefers port `8080`; if it is busy (`EADDRINUSE`) it tries the next 10 ports and
finally an OS-assigned port. The screen always shows the actual URL/port. The server is stopped
when the screen stops/is destroyed, and double starts are ignored.

### FTP / FTPS / SFTP upload

**"Upload Settings (FTP/SFTP)"** lets you enable uploading of every image and its `.json` annotation
to a remote server: protocol (FTP, FTPS or SFTP), host, port, username, password (or private key
for SFTP, with the password field as passphrase) and remote directory. Files go to
`<remote dir>/<session>/`. The password/key are encrypted with an Android Keystore AES-GCM key
before being stored. Uploads run in the background through WorkManager (network required), retry
with exponential backoff (up to 5 attempts) and the capture screen shows pending/done/failed counts
and the last error. Libraries: Apache commons-net (FTP/FTPS) and JSch (SFTP, `com.github.mwiede:jsch`).
SFTP accepts unknown host keys on first connect.

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
