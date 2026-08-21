package com.henny.checklist

import android.app.Application
import com.henny.checklist.notify.AlarmScheduler
import com.henny.checklist.notify.Notifications

/**
 * 프로세스가 만들어질 때 가장 먼저 불리는 곳. 화면(Activity)보다 앞선다.
 *
 * 알람 때문에 OS 가 프로세스만 깨우는 경우에는 MainActivity 가 아예 안 만들어진다.
 * 그런 경로로 들어와도 알림 채널이 있어야 하고 다음 알람도 걸려 있어야 하므로,
 * 화면이 아니라 여기에 둔다.
 *
 * 서버의 애플리케이션 부트스트랩과 비슷하지만 결정적인 차이가 있다.
 * 이 함수는 앱 수명에 한 번이 아니라 "프로세스가 새로 뜰 때마다" 불린다.
 * 하루에도 여러 번 죽었다 살아날 수 있으므로 무거운 초기화를 두면 안 된다.
 */
class HennyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        AlarmScheduler.reschedule(this)
    }
}
