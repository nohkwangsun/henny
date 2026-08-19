package com.henny.checklist.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.henny.checklist.data.Repository
import com.henny.checklist.data.Role
import com.henny.checklist.data.minuteToText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 알람은 "다음 한 개"만 예약한다.
 * 울릴 때마다 그 순간에 필요한 알림을 띄우고 다시 다음 한 개를 예약하는 방식이라
 * 할 일이 바뀌어도 예약을 지우고 다시 거는 뒷정리가 필요 없다.
 */
object AlarmScheduler {

    private const val PREFS = "henny_alarm"
    private const val KEY_LAST_FIRED = "lastFiredEpochMin"
    private const val REQUEST_CODE = 7001

    /** 앱이 오래 꺼져 있었어도 3시간 넘게 지난 알림은 다시 띄우지 않는다. */
    private const val CATCH_UP_MINUTES = 180L

    data class Fire(
        val at: LocalDateTime,
        val title: String,
        val text: String,
        val childId: String,
        val onlyIfIncomplete: Boolean,
        val channel: String,
        val tag: String
    )

    fun reschedule(context: Context) {
        Notifications.ensureChannels(context)
        val repo = Repository.get(context)
        val now = LocalDateTime.now()
        val next = events(repo, now.toLocalDate().minusDays(1), 3)
            .filter { it.at.isAfter(now) }
            .minByOrNull { it.at } ?: return

        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val millis = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            } else {
                // 정확 알람 권한이 없으면 몇 분 늦더라도 울리기는 하도록 둔다.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            }
        }
    }

    /** 알람이 울렸을 때 호출. 지금 시점에 울려야 할 알림을 모두 띄운다. */
    fun fireDue(context: Context) {
        val repo = Repository.get(context)
        val now = LocalDateTime.now()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowMin = now.atZone(ZoneId.systemDefault()).toEpochSecond() / 60
        val lastFired = prefs.getLong(KEY_LAST_FIRED, nowMin - 1)
        val floor = maxOf(lastFired, nowMin - CATCH_UP_MINUTES)

        events(repo, now.toLocalDate().minusDays(1), 3)
            .filter {
                val min = it.at.atZone(ZoneId.systemDefault()).toEpochSecond() / 60
                min > floor && min <= nowMin + 1
            }
            .sortedBy { it.at }
            .forEach { fire ->
                val remaining = repo.remaining(fire.childId, LocalDate.now())
                if (fire.onlyIfIncomplete && remaining == 0) return@forEach
                Notifications.show(
                    context = context,
                    id = fire.tag.hashCode(),
                    channel = fire.channel,
                    title = fire.title,
                    text = fire.text
                )
            }

        prefs.edit().putLong(KEY_LAST_FIRED, nowMin).apply()
    }

    /** [from]부터 [days]일치 알림 후보를 모두 만든다. */
    fun events(repo: Repository, from: LocalDate, days: Int): List<Fire> {
        val settings = repo.settings.value
        val plan = repo.plan.value
        val out = mutableListOf<Fire>()

        val targetChildren = when (settings.roleEnum) {
            Role.KID -> listOfNotNull(settings.childId.takeIf { it.isNotBlank() })
            Role.PARENT -> emptyList()
            Role.NONE -> emptyList()
        }

        repeat(days) { offset ->
            val date = from.plusDays(offset.toLong())
            val dow = date.dayOfWeek.value

            targetChildren.forEach { childId ->
                plan.reminders
                    .filter { it.childId == childId && it.enabled && dow in it.days }
                    .forEach { r ->
                        out += Fire(
                            at = date.atStartOfDay().plusMinutes(r.minute.toLong()),
                            title = if (r.onlyIfIncomplete) "아직 남은 할 일이 있어요" else "오늘의 할 일",
                            text = r.text,
                            childId = childId,
                            onlyIfIncomplete = r.onlyIfIncomplete,
                            channel = Notifications.CHANNEL_TODO,
                            tag = "rem:${r.id}:${date}"
                        )
                    }

                // 마감 시각이 있는 할 일은 정해진 시간 전에 한 번 더 찔러준다.
                repo.tasksFor(childId, date).forEach { task ->
                    val due = task.dueMinute ?: return@forEach
                    val remindAt = due - task.remindBefore
                    if (remindAt < 0) return@forEach
                    out += Fire(
                        at = date.atStartOfDay().plusMinutes(remindAt.toLong()),
                        title = task.title,
                        text = "${minuteToText(due)}까지예요. 지금 시작하면 딱 맞아요!",
                        childId = childId,
                        onlyIfIncomplete = true,
                        channel = Notifications.CHANNEL_TODO,
                        tag = "due:${task.id}:${date}"
                    )
                }
            }

            if (settings.roleEnum == Role.PARENT && settings.parentSummaryOn) {
                val summary = plan.children.joinToString("  ") { child ->
                    val tasks = repo.tasksFor(child.id, date)
                    val done = tasks.count { it.done }
                    "${child.name} $done/${tasks.size}"
                }
                if (summary.isNotBlank()) {
                    out += Fire(
                        at = date.atStartOfDay().plusMinutes(settings.parentSummaryMinute.toLong()),
                        title = "오늘 아이들 현황",
                        text = summary,
                        childId = "",
                        onlyIfIncomplete = false,
                        channel = Notifications.CHANNEL_SUMMARY,
                        tag = "sum:${date}"
                    )
                }
            }
        }
        return out
    }
}
