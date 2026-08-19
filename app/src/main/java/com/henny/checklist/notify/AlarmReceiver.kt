package com.henny.checklist.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.henny.checklist.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        val repo = Repository.get(app)
        repo.scope.launch(Dispatchers.Default) {
            try {
                // 알림을 띄우기 전에 최신 계획을 받아본다. 실패해도 로컬 기준으로 진행.
                runCatching { repo.sync() }
                AlarmScheduler.fireDue(app)
                AlarmScheduler.reschedule(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.henny.checklist.TICK"
    }
}
