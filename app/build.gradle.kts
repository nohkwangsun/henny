plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
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
        // Play 에 올릴 때마다 값이 커져야 하므로 CI 실행 번호를 쓴다.
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "DebugProbesKt.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }

    // Play 는 디버그 정보를 따로 받아 크래시 로그를 읽기 좋게 만들어 준다.
    androidResources {
        generateLocaleConfig = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
}
