package com.henny.checklist

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.henny.checklist.notify.AlarmScheduler
import com.henny.checklist.notify.Notifications
import com.henny.checklist.notify.ScheduleStore

/**
 * 이 앱은 껍데기다.
 *
 * 화면과 로직은 GitHub Pages 에 올라간 웹앱이 담당하고, 여기서는 두 가지만 한다.
 *   1. 그 웹앱을 띄운다        -> 배포하면 다음 실행 때 바로 최신이 된다
 *   2. 웹이 넘겨준 일정대로 알림을 띄운다 -> 서버 없이 정확한 시각에 울린다
 *
 * 그래서 이 APK 는 알림 방식을 바꿀 때가 아니면 다시 설치할 일이 없다.
 *
 * ---------------------------------------------------------------------------
 * 서버 개발과 다른 점 (여기서만 통하는 전제)
 *
 * - Activity 에는 main() 이 없다. 우리가 만들어서 실행하는 객체가 아니라,
 *   OS 가 만들고 OS 가 없앤다. 우리는 "생성됐을 때/보이기 시작할 때" 같은
 *   시점에 끼어들 뿐이다. 서블릿 컨테이너가 서블릿을 다루는 방식에 가깝다.
 *
 * - 프로세스가 언제든 죽는다. 사용자가 홈 버튼을 누르고 다른 앱을 몇 개 열면
 *   OS 가 메모리를 회수하려고 이 프로세스를 조용히 죽인다. 예고도, 종료 훅도
 *   보장되지 않는다. 그래서 "떠 있는 동안 메모리에 들고 있으면 된다"는 서버식
 *   가정을 쓸 수 없고, 남겨야 할 것은 그때그때 디스크에 적어야 한다.
 *   (이 앱에서는 알림 일정이 그렇다. ScheduleStore 참고)
 *
 * - 그래서 알림을 "앱이 살아서 기다리다가" 띄우지 않는다. OS 에 예약을 걸어
 *   두고 프로세스는 죽어도 된다. AlarmScheduler 가 그 일을 한다.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    /**
     * 안드로이드 13 부터 알림도 사용자 동의를 받아야 한다.
     *
     * 권한 요청은 비동기다. 시스템 다이얼로그가 뜨고, 그 사이 우리 Activity 가
     * 죽었다 다시 만들어질 수도 있다. 그래서 "요청하고 결과를 기다린다"가 아니라
     * 콜백을 미리 등록해 두는 구조다.
     *
     * 이 등록은 반드시 Activity 가 화면에 붙기 전(= 필드 초기화나 onCreate)에
     * 끝나야 한다. 나중에 부르면 런타임 예외가 난다. 프레임워크가 상태 복원
     * 시점에 콜백을 다시 연결해야 하기 때문이다.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 허락을 받았든 말든 일정은 다시 걸어 둔다. 거절했으면 어차피 안 뜬다.
            AlarmScheduler.reschedule(this)
        }

    /**
     * onCreate 는 "이 화면 객체가 처음 만들어졌다" 시점이다. 앱 시작과 다르다.
     * 화면 회전이나 시스템 설정 변경으로도 불릴 수 있다(아래 configChanges 참고).
     */
    @SuppressLint("SetJavaScriptEnabled")  // 우리 페이지만 띄우므로 JS 허용은 의도한 것
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)

        web = WebView(this).apply {
            // WebView 는 앱 안에 박아 넣은 크롬이다. 기본값이 "아무것도 안 되는"
            // 상태라 필요한 것을 하나씩 켜 줘야 한다.
            settings.javaScriptEnabled = true
            // 웹앱이 localStorage 에 자료를 담으므로 반드시 켜야 한다.
            // 끄면 조용히 실패하는 게 아니라 저장 자체가 안 돼 화면이 매번 빈다.
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 배포 즉시 반영이 목적이므로 캐시보다 네트워크를 먼저 본다.
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = true
            // 안쪽으로 민 자리(상태바 아래)는 WebView 자신의 배경색이 보인다.
            // 웹 배경과 다르면 띠가 생기므로 같은 값을 쓴다.
            setBackgroundColor(getColor(R.color.henny_background))

            // 여기서 JS 세계와 코틀린 세계가 이어진다. 웹에서는 전역 객체
            // window.HennyShell 로 보이고, @JavascriptInterface 가 붙은 메서드만
            // 건너온다. 아래 Bridge 클래스가 이 앱의 "공개 API" 전부다.
            addJavascriptInterface(Bridge(), "HennyShell")

            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    // 첫 실행에 네트워크가 없으면 흰 화면만 남는다. 안내라도 띄운다.
                    // 이미 한 번 열어 본 뒤라면 서비스 워커(web/sw.js)가 캐시에서
                    // 꺼내 주므로 여기까지 오지 않는다.
                    if (request?.isForMainFrame == true) {
                        view?.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null)
                    }
                }
            }
        }
        setContentView(web)

        // targetSdk 35 부터는 앱이 상태바·내비게이션바 뒤까지 그린다(edge-to-edge).
        // 그대로 두면 웹 화면 맨 윗줄이 시계와 겹친다. 시스템 막대 높이만큼
        // 안쪽으로 민다.
        //
        // 값을 상수로 박을 수 없다. 기기마다 다르고, 회전하면 바뀌고, 노치가 있는
        // 기기는 또 다르다. 그래서 OS 가 "지금 이 값이다"라고 알려줄 때마다 받는
        // 콜백 형태다. 화면이 바뀔 때마다 다시 불린다.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(web) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // 안드로이드의 뒤로 가기는 브라우저의 뒤로 가기와 다르다. 기본 동작은
        // "이 화면을 닫는다"라서, 웹에서 몇 단계 들어가 있어도 한 번에 앱이 꺼진다.
        // 웹 히스토리가 남아 있으면 그쪽을 먼저 쓰도록 가로챈다.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(APP_URL)
    }

    /**
     * onStart 는 화면이 사용자에게 보이기 시작할 때다. onCreate 와 달리
     * 앱을 잠깐 나갔다 돌아올 때마다 다시 불린다.
     *
     * 여기서 알람을 다시 거는 이유: 이 프로세스가 죽어 있는 동안 예약해 둔
     * 시각이 지나갔을 수 있다. AlarmScheduler 는 "다음 한 개"만 예약하는
     * 방식이라, 돌아왔을 때 그다음 것을 새로 걸어 줘야 한다.
     */
    override fun onStart() {
        super.onStart()
        AlarmScheduler.reschedule(this)
    }

    private fun onMain(block: () -> Unit) = runOnUiThread(block)

    /**
     * 웹에서 부르는 창구. 여기 있는 것만 웹이 쓸 수 있다.
     *
     * 주의할 점 두 가지.
     *
     * 1) 이 메서드들은 UI 스레드가 아니라 WebView 의 별도 스레드에서 불린다.
     *    화면이나 Activity 를 건드리는 일(다이얼로그, 화면 전환)은 반드시
     *    onMain 으로 감싸 UI 스레드로 넘겨야 한다. 안 그러면 예외가 난다.
     *    -- 서버로 치면 요청 처리 스레드에서 곧장 UI 툴킷을 만지는 셈이다.
     *
     * 2) addJavascriptInterface 는 그 WebView 에 로드된 "모든" 페이지에 열린다.
     *    남의 페이지를 열 수 있게 만들면 그 페이지도 이 함수들을 부를 수 있다.
     *    이 앱은 우리 주소 하나만 열기 때문에 안전하다. 외부 링크를 앱 안에서
     *    열도록 바꾼다면 그때는 화이트리스트가 필요해진다.
     */
    inner class Bridge {

        /**
         * 웹이 계산한 알림 일정을 넘겨받는다. [{at, title, body, tag}, ...]
         *
         * 껍데기는 이 JSON 을 해석하지 않는다. "언제 무엇을 띄울지"만 알면 되고,
         * 작업이 무엇인지 마일리지가 얼마인지는 알 필요가 없다. 이 경계 덕분에
         * 규칙이 바뀌어도 APK 를 다시 만들 일이 없다.
         */
        @JavascriptInterface
        fun setSchedule(json: String) {
            ScheduleStore.save(this@MainActivity, json)
            onMain { AlarmScheduler.reschedule(this@MainActivity) }
        }

        /**
         * 예전 네이티브 앱이 쓰던 파일을 읽어 웹에 넘긴다. 한 번만 쓰이고 끝나는
         * 이사용 통로다.
         *
         * 예전 앱은 자료를 filesDir/henny/*.json 에 뒀고 지금 앱은 WebView 의
         * localStorage 에 둔다. 저장 위치가 완전히 다른 곳이라, 앱을 덮어 설치하면
         * 파일은 그대로 있는데 새 코드가 그걸 못 봤다. 화면이 비어 보인 이유다.
         *
         * 읽기만 하고 원본은 지우지 않는다. 이관이 잘못돼도 되돌릴 수 있어야 한다.
         */
        @JavascriptInterface
        fun legacyData(): String {
            val dir = java.io.File(filesDir, "henny")
            if (!dir.isDirectory) return ""
            // 파일을 파싱했다가 다시 문자열로 만들지 않고, 읽은 원문을 그대로
            // 이어 붙여 넘긴다. 껍데기가 자료 형식을 아는 순간 형식이 바뀔 때마다
            // APK 를 다시 만들어야 하기 때문이다.
            val out = StringBuilder("{")
            var first = true
            fun put(key: String, raw: String) {
                if (!first) out.append(',')
                first = false
                out.append(org.json.JSONObject.quote(key)).append(':').append(raw)
            }
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (!f.isFile || !f.name.endsWith(".json")) return@forEach
                    val text = f.readText().trim()
                    if (text.isEmpty() || !text.startsWith("{")) return@forEach
                    // 형식이 깨진 파일을 넘기면 웹 쪽 JSON.parse 가 통째로 실패한다.
                    if (runCatching { org.json.JSONObject(text) }.isFailure) return@forEach
                    put(f.name, text)
                }
            }
            out.append('}')
            // 옮길 게 없으면 빈 문자열. 웹은 이걸 "이관할 것 없음"으로 읽는다.
            // "{}" 와 구분해야 하므로 바꾸지 말 것.
            return if (first) "" else out.toString()
        }

        /**
         * 설치된 APK 의 버전. 웹이 설정 화면에 띄워, 사용자가 "앱은 최신인지"를
         * 눈으로 확인할 수 있게 한다. 웹 버전과 앱 버전이 따로 노는 구조라
         * 둘 다 보여주는 편이 문제를 가릴 때 낫다.
         */
        @JavascriptInterface
        fun version(): String = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName}"
        }.getOrDefault("")

        @JavascriptInterface
        fun canNotify(): Boolean = Notifications.canPost(this@MainActivity)

        /**
         * 알림 권한 요청. 안드로이드 13(SDK 33) 을 기준으로 갈린다.
         * 그 아래 버전에는 "알림 권한"이라는 개념 자체가 없어서, 사용자가 설정에서
         * 직접 껐을 수 있을 뿐이다. 그래서 설정 화면을 열어 주는 것 말고 할 게 없다.
         */
        @JavascriptInterface
        fun requestNotify() = onMain {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
        }

        /**
         * 앱의 알림 설정 화면을 연다.
         *
         * runCatching 으로 감싼 이유: Intent 는 "이런 화면을 열어 줘"라는 요청이고,
         * 그 화면이 이 기기에 있다는 보장이 없다. 제조사가 설정 앱을 갈아 끼우는
         * 일이 흔해서(삼성·샤오미) 표준 Intent 가 없는 기기가 실제로 있다.
         * 없으면 예외가 나므로, 더 일반적인 "앱 정보" 화면으로 물러선다.
         */
        @JavascriptInterface
        fun openNotificationSettings() = onMain {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }.onFailure {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }

        /**
         * "정확한 시각 알람" 권한 화면을 연다.
         *
         * 안드로이드 12(SDK 31) 부터 정확한 시각에 울리는 알람은 따로 허락을 받는다.
         * 배터리를 많이 먹는 기능이라 막아 둔 것이다. 이게 없으면 알람이 몇 분에서
         * 몇십 분까지 밀린다. 자세한 배경은 docs/MOBILE-BASICS.md 참고.
         */
        @JavascriptInterface
        fun openAlarmSettings() = onMain {
            if (Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            }
        }
    }

    companion object {
        /**
         * 배포 주소. 여기만 바꾸면 다른 저장소로도 옮길 수 있다.
         *
         * 이 한 줄이 이 프로젝트 구조의 핵심이다. 앱에 화면을 넣는 대신 주소를
         * 넣었기 때문에, 화면을 고치는 일이 APK 와 무관해진다.
         */
        const val APP_URL = "https://nohkwangsun.github.io/henny/"

        /** 첫 실행에 네트워크가 없을 때 흰 화면 대신 띄우는 안내. */
        private const val OFFLINE_HTML = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font:16px -apple-system,"Noto Sans KR",sans-serif;padding:48px 24px;
            background:#f7f3ee;color:#1e2321;text-align:center}
            @media(prefers-color-scheme:dark){body{background:#14171a;color:#e8e5e1}}</style></head>
            <body><h2>연결할 수 없습니다</h2>
            <p>처음 실행할 때는 인터넷이 필요합니다.<br>연결 후 앱을 다시 열어주세요.</p>
            </body></html>
        """
    }
}
