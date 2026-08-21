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

/**
 * 알림을 실제로 띄우는 곳.
 *
 * ---------------------------------------------------------------------------
 * 채널(Channel) 이라는 개념
 *
 * 안드로이드 8 부터 모든 알림은 "채널"에 속해야 한다. 채널은 사용자가 직접 만지는
 * 설정 단위다. 사용자는 앱 알림 설정에서 채널별로 소리를 끄거나, 중요도를 낮추거나,
 * 그 채널만 차단할 수 있다.
 *
 * 중요한 점: 채널 설정의 주인은 사용자다. 한 번 만든 채널의 중요도·소리를 코드로
 * 다시 바꿀 수 없다. 사용자가 "조용히"로 바꿔 놨는데 앱이 도로 시끄럽게 만드는 것을
 * 막기 위해서다. 바꾸려면 채널 ID 를 새로 만드는 수밖에 없고, 그러면 사용자가 예전
 * 채널에 해 둔 설정은 버려진다.
 *
 * 이 앱은 성격이 다른 두 가지를 나눠 두었다.
 *   todo    - 작업자에게 "할 일 챙겨라". 놓치면 안 되므로 IMPORTANCE_HIGH.
 *   summary - 관리자에게 "오늘 이만큼 했다". 조용해도 되므로 DEFAULT.
 * 나눠 두면 관리자가 요약만 끄고 할 일 알림은 살려 두는 식이 가능하다.
 */
object Notifications {

    const val CHANNEL_TODO = "todo"
    const val CHANNEL_SUMMARY = "summary"

    /**
     * 채널을 만든다. 이미 있으면 아무 일도 없다(멱등).
     * 알림을 띄우기 전에 채널이 반드시 있어야 해서, 여러 진입점에서 마음 놓고 부른다.
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val todo = NotificationChannel(
            CHANNEL_TODO,
            "할 일 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "오늘 작업을 챙기라고 알립니다."
            enableVibration(true)
        }
        val summary = NotificationChannel(
            CHANNEL_SUMMARY,
            "하루 요약",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "작업자들이 오늘 얼마나 진행했는지 알려줍니다."
        }
        manager.createNotificationChannel(todo)
        manager.createNotificationChannel(summary)
    }

    /**
     * 지금 알림을 띄울 수 있는 상태인가.
     *
     * 안드로이드 13(33) 부터 알림은 위치·카메라처럼 사용자에게 물어야 하는 권한이 됐다.
     * 그 아래 버전에는 권한 자체가 없고, 사용자가 설정에서 껐는지만 확인할 수 있다.
     * 두 경우를 나눠 보는 이유다.
     *
     * 권한이 없을 때 notify() 는 예외를 던지지 않고 조용히 무시된다. 그래서 미리
     * 확인하지 않으면 "알림이 안 오는데 로그도 없는" 상황이 된다.
     */
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

        // 알림을 눌렀을 때 앱을 여는 동작. 알림은 우리 앱 밖(시스템 UI)에 떠 있으므로
        // "우리 대신 이 화면을 열어 달라"고 OS 에 위임하는 형태가 된다.
        // NEW_TASK/CLEAR_TOP: 이미 앱이 떠 있으면 새로 쌓지 않고 기존 화면으로 간다.
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(context, channel)
            // 작은 아이콘은 반드시 단색 실루엣이어야 한다. 안드로이드가 알파 채널만
            // 쓰고 색은 버리기 때문에, 색이 든 아이콘을 넣으면 흰 사각형이 된다.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // 기본 알림은 한 줄에서 잘린다. 할 일 목록을 다 보여주려면 이게 필요하다.
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)          // 누르면 알림 목록에서 사라진다
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()

        // id 가 같으면 새 알림이 쌓이지 않고 기존 것을 덮어쓴다. 호출부에서 tag 의
        // 해시를 넘겨 주므로, 같은 알림이 두 번 걸려도 목록에 하나만 남는다.
        // 권한이 도중에 취소되는 등의 경우에 대비해 감싼다.
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
