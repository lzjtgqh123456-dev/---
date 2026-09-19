package com.liuxue.assistant.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.liuxue.assistant.data.dict.QuizSettings
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 统一管理后台提醒任务的注册，App 启动时调用一次即可 */
object ReminderScheduler {

    fun scheduleAll(context: Context) {
        Notifications.ensureChannels(context)
        scheduleExpiryCheck(context)
        scheduleStudyCheck(context)
        scheduleQuiz(context)
        scheduleClassReminders(context)
        scheduleMemoReminders(context)
    }

    /** 备忘录提醒：每 15 分钟检查一次到点提醒（去重靠 notifiedFor 字段） */
    fun scheduleMemoReminders(context: Context) {
        val request = PeriodicWorkRequestBuilder<MemoReminderWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MemoReminderWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * 上课提醒：每 15 分钟检查一次；开关/提前时间/作用班级变化时都要重新调用本方法。
     * 关闭时取消任务。
     */
    fun scheduleClassReminders(context: Context) {
        val s = ClassReminderSettings(context)
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ClassReminderWorker.WORK_NAME)
        if (!s.enabled) return
        val request = PeriodicWorkRequestBuilder<ClassReminderWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        wm.enqueueUniquePeriodicWork(
            ClassReminderWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 用户点「立即检查」时触发一次上课提醒检查 */
    fun runClassReminderNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<ClassReminderWorker>().build()
        )
    }

    /**
     * 每日生词抽查提醒。
     * 时间由用户在抽查设置里定，所以设置变化时要重新调用本方法排期。
     * 用一次性任务链而不是 PeriodicWork：这样能精确落在用户选的时间点上。
     */
    fun scheduleQuiz(context: Context) {
        val s = QuizSettings(context)
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(QuizWorker.WORK_NAME)
        if (!s.enabled) return

        val delay = delayUntil(s.hour, s.minute)
        val req = androidx.work.OneTimeWorkRequestBuilder<QuizWorker>()
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        wm.enqueueUniqueWork(
            QuizWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            req
        )
    }

    /** 距下一个指定时刻还有多少分钟 */
    private fun delayUntil(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return TimeUnit.MILLISECONDS.toMinutes(target.timeInMillis - now.timeInMillis)
            .coerceAtLeast(1)
    }

    /** 学业提醒：每天上午 7:30 检查今天的课、待办作业、临近考试 */
    private fun scheduleStudyCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<StudyReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMinutes(hour = 7, minute = 30), TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            StudyReminderWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun scheduleExpiryCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<ExpiryCheckWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ExpiryCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 首次延迟到"今天上午 9 点"或"明天 9 点"，让提醒在合理时间出现 */
    private fun initialDelayMinutes(): Long = initialDelayMinutes(hour = 9, minute = 0)

    private fun initialDelayMinutes(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return TimeUnit.MILLISECONDS.toMinutes(target.timeInMillis - now.timeInMillis)
    }

    /** 手动触发一次检查（用户点"立即检查"时用） */
    fun runCheckNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<ExpiryCheckWorker>().build()
        )
    }
}
