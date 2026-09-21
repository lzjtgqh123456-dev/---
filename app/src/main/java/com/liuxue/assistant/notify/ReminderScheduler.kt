package com.liuxue.assistant.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.dict.QuizSettings
import com.liuxue.assistant.domain.ClassReminderLogic
import com.liuxue.assistant.domain.WeekCalc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
     * 上课提醒：
     *  - 主路径：为未来 7 天的每节课各排一个**一次性任务**，到「上课时间 - 提前 N 分钟」时触发，
     *    所以用户设置的提前量能真正兑现（15 分钟轮询做不到这一点）；
     *  - 兜底：保留一个 15 分钟的扫描任务，补发被系统省电/杀进程漏掉的一次性提醒；
     *  - 续排：每天一次把窗口往后推，用户长期不开 App 也不会断。
     *
     * 开关 / 提前时间 / 作用班级 / 课表变化时都要重新调用本方法。
     */
    fun scheduleClassReminders(context: Context) {
        val s = ClassReminderSettings(context)
        val wm = WorkManager.getInstance(context)
        if (!s.enabled) {
            wm.cancelUniqueWork(ClassReminderWorker.WORK_NAME)
            wm.cancelUniqueWork(ClassReminderTopUpWorker.WORK_NAME)
            wm.cancelAllWorkByTag(ClassReminderWorker.TAG_ONCE)
            return
        }

        val sweep = PeriodicWorkRequestBuilder<ClassReminderWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        wm.enqueueUniquePeriodicWork(
            ClassReminderWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            sweep
        )

        // 读课表要在后台线程；Fire-and-forget，失败不影响其它提醒任务注册
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { enqueueUpcomingClassReminders(context) }
        }

        val topUp = PeriodicWorkRequestBuilder<ClassReminderTopUpWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(6, TimeUnit.HOURS)
            .setConstraints(Constraints.NONE)
            .build()
        wm.enqueueUniquePeriodicWork(
            ClassReminderTopUpWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            topUp
        )
    }

    /**
     * 把未来 7 天里需要提醒的课各排一个一次性任务。
     * 用 `KEEP` + 稳定的任务名（课表 id + 开始时间 + 触发时间）保证幂等：
     * 重复调用不会排重，改了提前时间会生成一个新的触发时刻任务，旧任务到点自检时发现不一致会跳过。
     */
    suspend fun enqueueUpcomingClassReminders(context: Context) {
        val s = ClassReminderSettings(context)
        if (!s.enabled) return
        val dao = AppDatabase.get(context).studyDao()
        val semester = dao.activeSemester() ?: return
        val courses = dao.allCourses().associateBy { it.id }
        val now = System.currentTimeMillis()
        val occurrences = ClassReminderLogic.upcoming(
            schedules = dao.allSchedules(),
            coursesById = courses,
            semester = semester,
            now = now,
            minutesBefore = s.minutesBefore,
            className = s.className,
            horizonMillis = 7 * WeekCalc.MILLIS_PER_DAY
        ).take(200)

        val wm = WorkManager.getInstance(context)
        occurrences.forEach { o ->
            val data = Data.Builder()
                .putLong(ClassReminderWorker.KEY_SCHEDULE, o.schedule.id)
                .putLong(ClassReminderWorker.KEY_START, o.startMillis)
                .build()
            val delay = (o.triggerMillis - System.currentTimeMillis()).coerceAtLeast(60_000L)
            val req = OneTimeWorkRequestBuilder<ClassReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .setConstraints(Constraints.NONE)
                .addTag(ClassReminderWorker.TAG_ONCE)
                .build()
            val name = ClassReminderWorker.WORK_NAME + "_" + o.schedule.id + "_" +
                o.startMillis + "_" + o.triggerMillis
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
        }
    }

    /** 用户点「立即检查」时触发一次兜底扫描（合并成一条汇总通知） */
    fun runClassReminderNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ClassReminderWorker.WORK_NAME + "_now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ClassReminderWorker>().build()
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
        val req = OneTimeWorkRequestBuilder<QuizWorker>()
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        wm.enqueueUniqueWork(
            QuizWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
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
            OneTimeWorkRequestBuilder<ExpiryCheckWorker>().build()
        )
    }
}
