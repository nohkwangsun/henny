package com.henny.checklist.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅·앱 업데이트·시간대 변경 뒤에 알람을 다시 건다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.reschedule(context.applicationContext)
    }
}
