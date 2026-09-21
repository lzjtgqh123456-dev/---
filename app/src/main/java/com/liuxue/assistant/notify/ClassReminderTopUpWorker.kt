package com.liuxue.assistant.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 每天跑一次，把「未来 7 天」的上课提醒重新排一遍。
 *
 * 为什么要它：一次性提醒只排了未来 7 天；如果用户长期不打开 App，
 * 没有这个续排任务，第 8 天起就不会有提醒了。它由 ReminderScheduler 注册，
 * WorkManager 会跨重启保留。
 */
class ClassReminderTopUpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { ReminderScheduler.enqueueUpcomingClassReminders(applicationContext) }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "class_reminder_topup"
    }
}
