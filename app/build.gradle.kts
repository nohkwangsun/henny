/*
 * 안드로이드 빌드 스크립트. Gradle 이라 Java/Scala 쪽과 뿌리는 같지만,
 * android { } 블록은 안드로이드 플러그인이 얹은 것이라 생소한 개념이 몇 개 있다.
 * 아래 주석은 그것들만 짚는다. docs/MOBILE-BASICS.md 에 배경이 더 있다.
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 업로드 키는 저장소에 두지 않는다. CI 는 GitHub Secrets 에서 받아
 * 아래 환경 변수로 넘겨준다. 값이 없으면 서명하지 않고 빌드만 한다.
 *
 * 서명이 왜 중요한가: 안드로이드에서 APK 서명은 앱의 신원 그 자체다. 같은
 * applicationId 라도 서명이 다르면 OS 가 "다른 앱"으로 보고 덮어 설치를 거부한다.
 * 서명 키가 바뀌면 사용자는 앱을 지웠다 새로 깔아야 하고, 그러면 그 앱이 쓰던
 * 저장 공간(= 우리 경우 localStorage)이 통째로 사라진다.
 * 예전에 "업데이트했더니 자료가 없어졌다"가 나온 원인 중 하나가 이것이었다.
 */
val keystorePath: String? = System.getenv("HENNY_KEYSTORE")
val hasUploadKey: Boolean = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.henny.checklist"

    /**
     * compileSdk  어떤 버전의 SDK 로 컴파일할지. 최신으로 두면 최신 API 를 쓸 수 있다.
     *             기기에 요구하는 조건이 아니다.
     */
    compileSdk = 36

    defaultConfig {
        /** 스토어와 OS 가 앱을 식별하는 값. 한 번 정하면 사실상 못 바꾼다. */
        applicationId = "com.henny.checklist"

        /**
         * minSdk  이 버전 미만 기기에는 설치되지 않는다. 26 = 안드로이드 8.
         *         올리면 지원 기기가 줄고, 내리면 옛 API 분기 코드가 늘어난다.
         *
         * targetSdk  "이 버전까지 맞춰 만들었다"는 선언. 단순한 숫자가 아니라
         *         동작 계약이다. OS 는 이 값을 보고 어떤 규칙을 적용할지 정한다.
         *         예를 들어 35 이상이면 화면을 시스템 막대 뒤까지 그리게 하고(edge-to-edge),
         *         33 이상이면 알림 권한을 물어보게 한다.
         *         즉 이 숫자만 올려도 앱 동작이 바뀐다. 올릴 때는 그 버전에서 무엇이
         *         달라지는지 확인해야 한다. 실제로 상태바 글씨 겹침 문제가 이것 때문이었다.
         *         구글 플레이는 매년 이 값의 하한을 올리므로 계속 올려야 한다.
         */
        minSdk = 26
        targetSdk = 36

        /**
         * versionCode  정수. 스토어가 "더 새 버전인가"를 이 값으로만 판단한다.
         *              반드시 단조 증가해야 한다. CI 의 실행 번호를 그대로 쓴다.
         * versionName  사람이 읽는 문자열. 아무 의미 없다. 화면에 보여줄 뿐이다.
         */
        versionCode = (System.getenv("HENNY_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HENNY_VERSION_NAME") ?: "1.0"

        // 쓰지 않는 언어의 리소스를 빼서 APK 를 줄인다.
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
            /**
             * R8 이 쓰지 않는 코드를 지우고 이름을 줄인다(난독화 겸 축소).
             * 리플렉션이나 이름으로 찾는 코드가 있으면 여기서 깨진다. 이 앱은
             * 그런 게 없어서 규칙 파일이 비어 있다시피 하다.
             */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasUploadKey) signingConfigs.getByName("upload") else null
        }
        debug {
            // 접미사를 붙여 릴리스판과 나란히 설치할 수 있게 한다.
            // applicationId 가 달라지므로 OS 에는 완전히 다른 앱이고 저장소도 따로다.
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
//
// 의존성이 이 둘뿐이라는 게 이 구조의 증거다. 보통 안드로이드 앱은 여기에
// Compose 나 AppCompat, 이미지 로더, 네트워크 라이브러리가 줄줄이 붙는다.
// 그 자리를 전부 웹이 대신하고 있다.
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity:1.10.1")
}
