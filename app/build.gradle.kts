plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.yourname.watchreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourname.watchreader"
        minSdk = 27
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
        // CRITICAL: Ensure models aren't compressed
        androidResources {
            noCompress.add("tflite")
        }
        ndk {
                abiFilters.addAll(listOf("arm64-v8a"))
            }

    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // Fixed debug key (committed on purpose, it protects nothing): every CI build is signed
    // the same way, so `adb install -r` can upgrade in place and keep the app's settings.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Local HTTP server used to let a PC browse/download training captures over Wi-Fi.
    implementation(libs.nanohttpd)
    // Optional FTP/FTPS (commons-net) and SFTP (JSch) upload, run via WorkManager.
    implementation(libs.work.runtime.ktx)
    implementation(libs.commons.net)
    implementation(libs.jsch)
    // 2. ADD LiteRT and MediaPipe
    //implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14") {    
        exclude("org.tensorflow", "tensorflow-lite-api")
    }
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    // Runtime for the trained clock-reading model (assets/clock_reader.tflite).
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

}
