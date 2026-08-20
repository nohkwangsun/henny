package com.henny.checklist

import android.app.Application
import com.henny.checklist.notify.AlarmScheduler
import com.henny.checklist.notify.Notifications

class HennyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        AlarmScheduler.reschedule(this)
    }
}
