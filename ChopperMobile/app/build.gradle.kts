plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.choppermobile"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.choppermobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)

    // Offline “Hey Chopper” and “Hey Chop” wake-word engine
    implementation(
        files("libs/sherpa-onnx-1.13.6.aar")
    )

    // Inline Autofill suggestions for Android 11+ keyboards
    implementation(
        "androidx.autofill:autofill:1.3.0"
    )

    // Local Chopper AI model
    implementation(
        "com.google.mediapipe:tasks-genai:0.10.24"
    )

    // On-device OCR
    implementation(
        "com.google.mlkit:text-recognition:16.0.1"
    )

    // On-device image and object recognition
    implementation(
        "com.google.mlkit:image-labeling:17.0.9"
    )

    // Face location/quality only. Identity verification is intentionally separate.
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Visible, lifecycle-aware front-camera security capture.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // Local Room database
    implementation(
        libs.androidx.room.runtime
    )

    // Generates Room database code
    ksp(
        libs.androidx.room.compiler
    )

    // Fingerprint, face unlock, or device PIN protection
    implementation(
        "androidx.biometric:biometric:1.1.0"
    )

    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )
}
