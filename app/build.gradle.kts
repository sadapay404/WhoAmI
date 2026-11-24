plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Stable sideload identity: committed keystore so EVERY build (CI + local)
// signs with the same certificate → future APKs update in place, no uninstall.
// Previous builds used GitHub's ephemeral per-run debug key (random each time),
// which is why older installs can't be upgraded in place.
val stableKeystore = rootProject.file("keystore/whoami-ci.jks")

android {
    namespace = "com.whoami.guard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whoami.guard"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.0"
    }

    signingConfigs {
        if (stableKeystore.exists()) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = "whoami2026"
                keyAlias = "whoami"
                keyPassword = "whoami2026"
            }
        }
    }

    buildTypes {
        debug {
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isMinifyEnabled = false
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            } else {
                // Fallback for clones without the keystore: local debug key.
                signingConfig = signingConfigs.getByName("debug")
            }
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
    // VideoCapture/Recorder — intruder video pipeline
    implementation("androidx.camera:camera-video:1.4.1")

    // ML Kit face detection — bundled model, works fully offline
    implementation("com.google.mlkit:face-detection:16.1.5")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")

    // TensorFlow Lite — runs MobileFaceNet embeddings on-device
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
