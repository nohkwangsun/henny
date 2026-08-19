package com.henny.checklist.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.henny.checklist.MainActivity
import com.henny.checklist.R

object Notifications {

    const val CHANNEL_TODO = "todo"
    const val CHANNEL_SUMMARY = "summary"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val todo = NotificationChannel(
            CHANNEL_TODO,
            "할 일 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "오늘 할 일을 챙기라고 알려줍니다."
            enableVibration(true)
        }
        val summary = NotificationChannel(
            CHANNEL_SUMMARY,
            "하루 요약",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "아이들이 오늘 얼마나 했는지 알려줍니다."
        }
        manager.createNotificationChannel(todo)
        manager.createNotificationChannel(summary)
    }

    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    fun show(context: Context, id: Int, channel: String, title: String, text: String) {
        if (!canPost(context)) return
        ensureChannels(context)

        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
