plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whoami.guard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whoami.guard"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-phase2"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("tflite")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // CameraX — preview + still capture for enrollment and checks
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // ML Kit face detection — bundled model, works fully offline
    implementation("com.google.mlkit:face-detection:16.1.6")

    // TensorFlow Lite — runs MobileFaceNet embeddings on-device
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
