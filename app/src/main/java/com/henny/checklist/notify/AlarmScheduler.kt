package com.henny.checklist.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 알람은 "다음 한 개"만 예약한다.
 * 울릴 때마다 그 순간에 필요한 알림을 띄우고 다시 다음 한 개를 예약하므로,
 * 일정이 바뀌어도 예약을 지우고 다시 거는 뒷정리가 없다.
 */
object AlarmScheduler {

    private const val REQUEST_CODE = 7001

    /** 오래 꺼져 있었어도 3시간 넘게 지난 알림은 다시 띄우지 않는다. */
    private const val CATCH_UP_MS = 3 * 60 * 60 * 1000L

    fun reschedule(context: Context) {
        Notifications.ensureChannels(context)
        val now = System.currentTimeMillis()
        val next = ScheduleStore.load(context)
            .filter { it.at > now }
            .minByOrNull { it.at } ?: return

        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
            } else {
                // 정확 알람 권한이 없으면 몇 분 늦더라도 울리기는 하도록 둔다.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
            }
        }
    }

    /** 알람이 울렸을 때. 지금 시점에 띄워야 할 것을 모두 띄운다. */
    fun fireDue(context: Context) {
        val now = System.currentTimeMillis()
        val floor = maxOf(ScheduleStore.lastFiredAt(context), now - CATCH_UP_MS)

        ScheduleStore.load(context)
            .filter { it.at in (floor + 1)..(now + 60_000) }
            .sortedBy { it.at }
            .forEach { entry ->
                Notifications.show(
                    context = context,
                    id = entry.tag.hashCode(),
                    channel = if (entry.tag.startsWith("sum:")) Notifications.CHANNEL_SUMMARY
                    else Notifications.CHANNEL_TODO,
                    title = entry.title,
                    text = entry.body
                )
            }

        ScheduleStore.markFired(context, now)
    }
}
