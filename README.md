# Watch Reader AI (LiteRt)

A local, private Android app that uses on-device Generative AI to read the time from analog watches.

## 🚀 Features
- **100% Offline:** No data leaves the device.
- **Recognize Watches Automatically:** New functionality now enables the app to detect and recognize watches within a captured image, and identify their coordinates.
- **Powered by Gemini Nano:**  ML Kit   API.

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
