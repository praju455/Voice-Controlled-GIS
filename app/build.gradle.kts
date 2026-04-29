plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.defense.tacticalmap"
    compileSdk = 34
    ndkVersion = "30.0.14904198"

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

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
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
    
    packaging {
        resources {
            excludes += "/META-INF/AL2.0"
            excludes += "/META-INF/LGPL2.1"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
    
    viewBinding {
        enable = true
    }
    
    aaptOptions {
        noCompress("tflite", "mbtiles")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // MapLibre Native
    implementation("org.maplibre.gl:android-sdk:10.2.0")
    
    // Vosk Offline Speech Recognition
    implementation("com.alphacephei:vosk-android:0.3.32") {
        exclude(group = "net.java.dev.jna")
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    
    // Offline SpatiaLite SQLite wrapper and TFLite
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-task-text:0.4.4")
    
    // GraphHopper for Offline Routing
    implementation("com.graphhopper:graphhopper-core:8.0")
    
    // LeakCanary for memory profiling (debug only)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.13")
}
