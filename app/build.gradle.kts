plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tasabcv.widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tasabcv.widget"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
    }

    signingConfigs {
        create("tasa") {
            storeFile = file("tasabcv.keystore")
            storePassword = "tasabcv"
            keyAlias = "tasabcv"
            keyPassword = "tasabcv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Fixed key in the repo, so every new build installs over the old one as an update.
            signingConfig = signingConfigs.getByName("tasa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
