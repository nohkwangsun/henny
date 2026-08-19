package com.henny.checklist

import android.app.Application
import com.henny.checklist.data.Repository
import com.henny.checklist.notify.AlarmScheduler
import com.henny.checklist.notify.Notifications

class HennyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repository.get(this)
        Notifications.ensureChannels(this)
        AlarmScheduler.reschedule(this)
    }
}
