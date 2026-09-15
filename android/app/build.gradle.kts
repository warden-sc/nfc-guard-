plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nfcguard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nfcguard.app"
        minSdk = 26   // HCE + LINEAR_ACCELERATION 안정 지원 기준
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-scaffold"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
