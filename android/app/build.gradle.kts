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
        // server-java 주소. 에뮬레이터에서 로컬 서버는 10.0.2.2, 실배포는 HTTPS 주소로 교체
        buildConfigField("String", "BACKEND_URL", "\"http://10.0.2.2:8080\"")
    }

    buildFeatures {
        buildConfig = true
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
