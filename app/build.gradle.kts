plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.defense.tacticalmap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.defense.tacticalmap"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    viewBinding {
        enable = true
    }
    
    aaptOptions {
        noCompress("tflite", "mbtiles")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // MapLibre Native
    implementation("org.maplibre.gl:android-sdk:10.2.0")
    
    // LeakCanary for memory profiling (debug only)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.13")
}
