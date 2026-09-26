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

        // LiteRT ships a native library per ABI. The iQOO is arm64; x86_64 keeps the
        // emulator working. Dropping the other two saves about 10 MB of APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    // Room 3D Scan: on-device motion tracking and surface detection. Adds no permissions
    // of its own beyond querying for the ARCore service.
    implementation("com.google.ar:core:1.56.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
