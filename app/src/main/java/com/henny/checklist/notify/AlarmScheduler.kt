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
 *
 * ---------------------------------------------------------------------------
 * AlarmManager 는 cron 이 아니다
 *
 * 서버에서 "7시 30분에 뭘 해라"는 cron 이나 스케줄러 스레드로 끝난다. 프로세스가
 * 계속 떠 있는 게 전제이기 때문이다. 폰에서는 그 전제가 없다.
 *
 *   - 우리 프로세스는 사용자가 앱을 나가는 순간 언제든 죽을 수 있다.
 *     따라서 앱 안의 타이머·코루틴·스레드는 알림 수단이 될 수 없다.
 *   - 그래서 "OS 야, 이 시각에 나를 깨워 줘"라고 예약을 맡긴다. 이게 AlarmManager 다.
 *     예약은 OS 가 들고 있으므로 우리 프로세스가 죽어도 살아남는다.
 *
 * 다만 OS 는 이 약속을 지킬 의무가 없다. 배터리 때문이다.
 *
 *   - Doze: 화면이 꺼지고 기기가 가만히 있으면 OS 가 절전 모드로 들어가 예약된
 *     알람을 몇 분~몇십 분씩 모아 두었다가 한꺼번에 처리한다.
 *   - 제조사 절전: 삼성 One UI, 샤오미 MIUI 등은 이보다 더 공격적으로, 자주 안 쓰는
 *     앱을 아예 재워 알람을 건너뛰기도 한다. 표준 API 로는 손쓸 수 없고 사용자가
 *     설정에서 예외로 지정해야 한다. (설정 화면 안내가 앱에 들어 있는 이유)
 *
 * 아래에서 setExactAndAllowWhileIdle 을 쓰는 게 이 문제에 대한 답이다.
 * "정확히, 절전 중이어도" 울려 달라는 뜻이고, 안드로이드 12 부터는 이걸 쓰려면
 * 사용자 허락(SCHEDULE_EXACT_ALARM)이 따로 필요하다.
 */
object AlarmScheduler {

    /**
     * PendingIntent 를 구분하는 번호. 같은 번호로 다시 만들면 기존 예약을
     * 덮어쓴다. 이 앱은 예약을 하나만 유지하므로 상수 하나로 충분하다.
     * (덮어쓰기가 곧 "이전 예약 취소"라서 따로 cancel 할 일이 없다.)
     */
    private const val REQUEST_CODE = 7001

    /** 오래 꺼져 있었어도 3시간 넘게 지난 알림은 다시 띄우지 않는다. */
    private const val CATCH_UP_MS = 3 * 60 * 60 * 1000L

    /**
     * 앞으로 울릴 것 중 가장 이른 하나를 OS 에 예약한다.
     *
     * 여러 번 불러도 안전하다(멱등). 같은 REQUEST_CODE 로 덮어쓰기 때문이다.
     * 그래서 앱이 열릴 때마다, 알람이 울릴 때마다, 부팅 뒤에 마음 놓고 부른다.
     */
    fun reschedule(context: Context) {
        Notifications.ensureChannels(context)
        val now = System.currentTimeMillis()
        val next = ScheduleStore.load(context)
            .filter { it.at > now }
            .minByOrNull { it.at } ?: return

        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        // PendingIntent 는 "이 Intent 를 나중에, 내 권한으로 대신 실행해 줘"라고
        // OS 에 넘기는 인증서 같은 것이다. 알람이 울리는 시점엔 우리 프로세스가
        // 없을 수도 있어서, OS 가 우리 대신 이걸 발사한다.
        // FLAG_IMMUTABLE 은 받는 쪽이 내용을 바꿔치기하지 못하게 막는다(안드로이드 12+ 필수).
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            // 안드로이드 12(31) 부터 정확 알람은 사용자 허락이 필요하다.
            // canScheduleExactAlarms() 가 false 인데 그냥 exact 를 부르면 예외가 난다.
            val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            if (exact) {
                // RTC_WAKEUP: 벽시계 기준, 잠든 기기를 깨워서라도 울린다.
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
            } else {
                // 정확 알람 권한이 없으면 몇 분 늦더라도 울리기는 하도록 둔다.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
            }
        }
    }

    /**
     * 알람이 울렸을 때. 지금 시점에 띄워야 할 것을 모두 띄운다.
     *
     * "하나만 예약했는데 왜 여러 개를 띄우나" — 예약은 하나지만 그사이 같은 시각에
     * 걸린 알림이 여럿일 수 있고, 절전으로 밀려 여러 개가 한꺼번에 만기될 수도 있다.
     *
     * 두 가지 경계로 중복과 뒷북을 막는다.
     *   - lastFiredAt 보다 뒤엣것만: 같은 알림을 두 번 띄우지 않는다.
     *   - now - 3시간 보다 뒤엣것만: 폰이 하루 꺼져 있었다고 어제 알림이
     *     우르르 쏟아지면 안 된다.
     * 뒤쪽 여유 60초는 알람이 살짝 일찍 깨어났을 때를 위한 것이다.
     */
    fun fireDue(context: Context) {
        val now = System.currentTimeMillis()
        val floor = maxOf(ScheduleStore.lastFiredAt(context), now - CATCH_UP_MS)

        ScheduleStore.load(context)
            .filter { it.at in (floor + 1)..(now + 60_000) }
            .sortedBy { it.at }
            .forEach { entry ->
                Notifications.show(
                    context = context,
                    // 같은 tag 는 같은 알림으로 취급되어 덮어쓰인다. 웹이 tag 를
                    // "rem:<id>:<날짜>" 처럼 만들어 주므로 중복이 쌓이지 않는다.
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
