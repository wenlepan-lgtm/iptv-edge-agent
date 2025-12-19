plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.joctv.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.joctv.agent"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        noCompress += listOf("tflite", "mdl", "conf", "json", "txt", "uuid")
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.leanback)
    implementation(libs.lifecycle.runtime.ktx)
    
    // AndroidX AppCompat and Activity dependencies
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    
    // Vosk Android SDK
    implementation("com.alphacephei:vosk-android:0.3.75")
}