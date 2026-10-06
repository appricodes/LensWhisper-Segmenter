import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. `keystore.properties` is gitignored — it holds storeFile, storePassword,
// keyAlias and keyPassword. Without it a release build still runs, it just comes out unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
} else {
    null
}

android {
    namespace = "com.appricodes.lens_whisper.segmenter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appricodes.lens_whisper.segmenter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }

    // The service's AIDL and the sample client live in api/ (Apache-2.0, so other apps can copy
    // them) rather than under app/ (AGPL-3.0) — see api/LICENSE. Both are compiled here too, so
    // the published client is always known to build against the published AIDL.
    sourceSets {
        getByName("main") {
            aidl.srcDirs("../api/aidl")
            java.srcDirs("../api/client")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    androidResources {
        // The model is memory-mapped straight out of the APK, which needs it stored uncompressed.
        noCompress += "tflite"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    // Plain LiteRT (the classic TFLite Interpreter API) runs the YOLOv8 model, CPU only.
    implementation(libs.litert) {
        // LiteRT's optional AI-pack model delivery pulls in proprietary Play libraries
        // (play-services-basement/-tasks, Play Core), which an AGPL-3.0 app can't ship.
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
