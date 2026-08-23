package com.henny.checklist.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 예약해 둔 알람 시각이 되면 OS 가 여기를 부른다.
 *
 * BroadcastReceiver 는 앱이 실행 중이 아니어도 OS 가 프로세스를 잠깐 띄워서까지
 * 부른다. 알림이 "앱을 안 열어도" 오는 이유가 이것이다.
 *
 * 대신 제약이 있다. onReceive 는 메인 스레드에서 불리고, 몇 초 안에 끝나야 한다.
 * 오래 끌면 ANR(응답 없음)로 죽는다. 네트워크 호출 같은 건 여기서 하면 안 된다.
 * 여기서는 저장된 일정을 읽어 알림을 띄우고 다음 알람을 다시 거는 것뿐이라
 * 전부 로컬 파일 접근이고 금방 끝난다.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 여기 넘어오는 context 는 수명이 짧다. 오래 쓰면 누수가 되므로
        // 애플리케이션 것으로 바꿔 넘긴다.
        val app = context.applicationContext
        AlarmScheduler.fireDue(app)
        // 방금 것을 소진했으니 그다음 한 개를 새로 건다. 이 두 줄이 이어지면서
        // 알람 하나로 무한히 이어지는 사슬이 된다.
        AlarmScheduler.reschedule(app)
    }

    companion object {
        const val ACTION_TICK = "com.henny.checklist.TICK"
    }
}
