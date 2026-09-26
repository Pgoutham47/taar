plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.taar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taar"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // LiteRT and SceneView ship native libraries per ABI. The iQOO, like nearly every
        // phone since 2019, is arm64; the others would add about 25 MB for no device.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // The golden fixtures live beside the module rather than in assets: unit tests
    // read them from disk, and shipping 30 KB of CSV in the APK serves nobody.
    testOptions.unitTests.all { it.workingDir = project.projectDir }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // On-device arc model (assets/taar_arc.tflite). 1.4.x rather than 2.x: 2.x pulls in
    // lifecycle 2.10 and guava, and this needs nothing but the interpreter.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    // Room 3D Scan: camera, surface detection and 3D markers over ARCore. 2.3.0 is the
    // newest release built for Kotlin 2.0; later ones need Kotlin 2.4.
    implementation("io.github.sceneview:arsceneview:2.3.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
