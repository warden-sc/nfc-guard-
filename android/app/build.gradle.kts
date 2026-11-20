plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 로컬/CI에서 넘기는 설정값: ~/.gradle/gradle.properties 또는 -P옵션 (저장소에 커밋하지 않음)
fun prop(name: String): String? = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.nfcguard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nfcguard.app"
        minSdk = 26   // HCE + LINEAR_ACCELERATION 안정 지원 기준
        targetSdk = 36 // Google Play: 2026-08-31부터 신규 앱/업데이트는 API 36 타깃 필수
        // Play 업로드마다 versionCode는 반드시 증가해야 함
        versionCode = (prop("nfcguard.versionCode") ?: "1").toInt()
        versionName = prop("nfcguard.versionName") ?: "0.1.0"
        // server-java 주소. 기본값은 에뮬레이터 -> PC 로컬 서버(10.0.2.2).
        // 실기기 + `adb reverse tcp:8080 tcp:8080` 이면 -Pnfcguard.backendUrl=http://localhost:8080
        // (또는 ~/.gradle/gradle.properties 에 nfcguard.backendUrl=...). 실배포는 HTTPS 주소로.
        val backendUrl = prop("nfcguard.backendUrl") ?: "http://10.0.2.2:8080"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        // 개인정보처리방침 공개 URL (Play Console에도 같은 주소 등록). 기본: 서버가 서빙하는 정적 페이지
        val privacyUrl = prop("nfcguard.privacyPolicyUrl") ?: "$backendUrl/privacy.html"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyUrl\"")
        // Play Integrity: Play Console에 연결한 Google Cloud 프로젝트 번호 (0 = 지정 안 함)
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${prop("nfcguard.cloudProjectNumber") ?: "0"}L")
        // ml-python/model_export.py --generate-key 가 출력한 공개키(base64). 비우면 서명 검증 생략(개발용)
        buildConfigField("String", "MODEL_SIGNING_PUBLIC_KEY", "\"\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // 업로드 키 서명 (Play App Signing 사용 시 이 키는 "업로드 키"). 키 파일·비밀번호는 커밋 금지
    signingConfigs {
        create("upload") {
            prop("nfcguard.signing.storeFile")?.let { storeFile = file(it) }
            storePassword = prop("nfcguard.signing.storePassword")
            keyAlias = prop("nfcguard.signing.keyAlias")
            keyPassword = prop("nfcguard.signing.keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 코드 축소·난독화 + 미사용 리소스 제거
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 서명 정보가 없으면 서명 안 된 AAB/APK (CI 빌드 검증용)
            if (prop("nfcguard.signing.storeFile") != null) signingConfig = signingConfigs.getByName("upload")
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
    implementation("androidx.activity:activity-ktx:1.9.3") // enableEdgeToEdge
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.google.android.play:integrity:1.4.0")

    testImplementation("junit:junit:4.13.2")
}
