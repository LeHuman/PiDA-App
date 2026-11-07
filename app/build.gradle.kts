plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pida.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pida.app"
        minSdk = 30
        targetSdk = 34
        // CI passes -PversionName/-PversionCode: tag builds use the tag
        // (x.x.x, leading v optional), other builds use the commit sha;
        // versionCode is derived monotonically there (100 + run number).
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 3
        versionName = (project.findProperty("versionName") as String?) ?: "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // CI signs with the shared debug keystore (-PdebugKeystoreFile)
            // so pipeline APKs upgrade over locally-installed debug builds.
            val keystore = project.findProperty("debugKeystoreFile") as String?
            if (keystore != null && file(keystore).isFile) {
                signingConfig = signingConfigs.create("sharedDebug") {
                    storeFile = file(keystore)
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.reactivestreams:reactive-streams:1.0.4")
}

