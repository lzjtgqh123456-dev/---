package com.liuxue.assistant

import android.app.Application
import com.liuxue.assistant.notify.ReminderScheduler

class AssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 注册后台提醒任务（幂等）
        ReminderScheduler.scheduleAll(this)
    }
}
