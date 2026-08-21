package com.henny.checklist.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 재부팅·앱 업데이트·시간대 변경 뒤에 알람을 다시 건다.
 *
 * AlarmManager 에 걸어 둔 예약은 기기를 껐다 켜면 전부 사라진다. OS 가 예약을
 * 영구 저장하지 않기 때문이다. 앱을 업데이트해도 마찬가지다.
 * 이걸 모르고 넘어가면 "며칠 잘 오다가 어느 날부터 안 온다"가 된다.
 *
 * 어떤 신호에 반응할지는 AndroidManifest.xml 의 intent-filter 에 적혀 있다.
 * (BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED)
 * 시각을 epoch milliseconds 로 계산해 두었으므로 시간대가 바뀌면 벽시계 기준이
 * 달라진다. 그래서 시간대 변경도 다시 걸어야 하는 신호다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.reschedule(context.applicationContext)
    }
}
