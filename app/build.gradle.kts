plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 업로드 키는 저장소에 두지 않는다. CI 는 GitHub Secrets 에서 받아
 * 아래 환경 변수로 넘겨준다. 값이 없으면 서명하지 않고 빌드만 한다.
 */
val keystorePath: String? = System.getenv("HENNY_KEYSTORE")
val hasUploadKey: Boolean = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.henny.checklist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.henny.checklist"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("HENNY_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HENNY_VERSION_NAME") ?: "1.0"
        resourceConfigurations += listOf("ko", "en")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("HENNY_STORE_PASSWORD")
                keyAlias = System.getenv("HENNY_KEY_ALIAS")
                keyPassword = System.getenv("HENNY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasUploadKey) signingConfigs.getByName("upload") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

// 껍데기는 WebView 를 띄우고 알림을 거는 것뿐이라 화면 라이브러리가 필요 없다.
// 그래서 APK 가 아주 작고 빌드도 빠르다.
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity:1.10.1")
}
