package com.henny.checklist.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        AlarmScheduler.fireDue(app)
        AlarmScheduler.reschedule(app)
    }

    companion object {
        const val ACTION_TICK = "com.henny.checklist.TICK"
    }
}
