package com.henny.checklist

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.henny.checklist.data.Repository
import com.henny.checklist.notify.AlarmScheduler
import com.henny.checklist.notify.Notifications
import com.henny.checklist.ui.AppRoot
import com.henny.checklist.ui.HennyTheme
import com.henny.checklist.ui.LocalNotificationRequester

class MainActivity : ComponentActivity() {

    private lateinit var repo: Repository

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            AlarmScheduler.reschedule(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = Repository.get(this)
        Notifications.ensureChannels(this)

        setContent {
            HennyTheme {
                CompositionLocalProvider(
                    LocalNotificationRequester provides { askForNotifications() }
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(Modifier.safeDrawingPadding()) {
                            AppRoot(repo)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 화면에 보이는 동안은 계속 맞춰 둔다. 들어오자마자 한 번, 그 뒤로는 주기 확인.
        repo.startLiveSync()
        AlarmScheduler.reschedule(this)
    }

    override fun onStop() {
        super.onStop()
        // 확인 루프를 멈추고, 미뤄둔 업로드는 마저 끝낸다.
        repo.stopLiveSync()
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
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
    }
}
