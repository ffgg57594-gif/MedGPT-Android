plugins {
    id("com.android.application")
}

android {
    namespace = "com.drarabi.medvision"
    compileSdk = 36
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.drarabi.medvision"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.3"

        // NDK/CMake for native security layer
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cppFlags += "-fvisibility=hidden"
                cppFlags += "-fstack-protector-strong"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
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

    // NDK build configuration
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Packaging options for native libraries
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity:1.8.2")

    // Play Integrity API (for device integrity verification)
    implementation("com.google.android.play:integrity:1.3.0")
    implementation("com.android.billingclient:billing:7.1.1")
}
