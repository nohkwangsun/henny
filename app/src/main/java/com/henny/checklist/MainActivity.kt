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
import androidx.lifecycle.lifecycleScope
import com.henny.checklist.data.Repository
import com.henny.checklist.notify.AlarmScheduler
import com.henny.checklist.notify.Notifications
import com.henny.checklist.ui.AppRoot
import com.henny.checklist.ui.HennyTheme
import com.henny.checklist.ui.LocalNotificationRequester
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repo: Repository

    private val notificationPerassignment =
        registerForActivityResult(ActivityResultContracts.RequestPerassignment()) {
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
        // 앱을 열 때마다 최신 계획을 받아오고 다음 알람을 다시 건다.
        lifecycleScope.launch { repo.sync() }
        AlarmScheduler.reschedule(this)
    }

    override fun onStop() {
        super.onStop()
        // 체크해 둔 걸 미뤄둔 채로 앱을 벗어나지 않게 한다.
        repo.flushPush()
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPerassignment.launch(android.Manifest.perassignment.POST_NOTIFICATIONS)
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
