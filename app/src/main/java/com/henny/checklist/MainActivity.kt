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
 *   1. 그 웹앱을 띄운다  -> 배포하면 다음 실행 때 바로 최신이 된다
 *   2. 웹이 넘겨준 일정대로 알림을 띄운다  -> 서버 없이 정확한 시각에 울린다
 *
 * 그래서 이 APK 는 알림 방식을 바꿀 때가 아니면 다시 설치할 일이 없다.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            AlarmScheduler.reschedule(this)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            // 웹앱이 localStorage 에 자료를 담으므로 반드시 켜야 한다.
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 배포 즉시 반영이 목적이므로 캐시보다 네트워크를 먼저 본다.
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = true
            addJavascriptInterface(Bridge(), "HennyShell")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    // 첫 실행에 네트워크가 없으면 흰 화면만 남는다. 안내라도 띄운다.
                    if (request?.isForMainFrame == true) {
                        view?.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null)
                    }
                }
            }
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(APP_URL)
    }

    override fun onStart() {
        super.onStart()
        // 껍데기가 잠든 사이 시각이 지났을 수 있으니 다시 건다.
        AlarmScheduler.reschedule(this)
    }

    private fun onMain(block: () -> Unit) = runOnUiThread(block)

    /** 웹에서 부르는 창구. 여기 있는 것만 웹이 쓸 수 있다. */
    inner class Bridge {

        /** 웹이 계산한 알림 일정을 넘겨받는다. [{at, title, body, tag}, ...] */
        @JavascriptInterface
        fun setSchedule(json: String) {
            ScheduleStore.save(this@MainActivity, json)
            onMain { AlarmScheduler.reschedule(this@MainActivity) }
        }

        @JavascriptInterface
        fun version(): String = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName}"
        }.getOrDefault("")

        @JavascriptInterface
        fun canNotify(): Boolean = Notifications.canPost(this@MainActivity)

        @JavascriptInterface
        fun requestNotify() = onMain {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
        }

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
        /** 배포 주소. 여기만 바꾸면 다른 저장소로도 옮길 수 있다. */
        const val APP_URL = "https://nohkwangsun.github.io/henny/"

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
