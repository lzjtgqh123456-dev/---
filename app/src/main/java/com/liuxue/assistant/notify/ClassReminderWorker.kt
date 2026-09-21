package com.liuxue.assistant.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.study.Course
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Semester
import com.liuxue.assistant.domain.ClassFilter
import com.liuxue.assistant.domain.ClassReminderLogic
import com.liuxue.assistant.domain.WeekCalc

/**
 * 上课提醒 Worker，两种模式：
 *
 * 1. **精确模式（带 inputData）**：由 [ReminderScheduler.enqueueUpcomingClassReminders] 为每一节课
 *    排一个一次性任务，到「上课时间 - 提前 N 分钟」时触发。这是主路径，时间准。
 * 2. **兜底扫描（无 inputData）**：每 15 分钟跑一次，补发那些一次性任务因为系统省电/被杀而漏掉的课。
 *    只补「已经到点但还没上课」的，绝不提前；多节课命中时合并成一条汇总通知，避免刷屏。
 *
 * 同一节课用 `课表 id + 开始时间` 去重（SharedPreferences，带锁防并发重复）。
 */
class ClassReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = ClassReminderSettings(ctx)
        if (!settings.enabled) return Result.success()

        val dao = AppDatabase.get(ctx).studyDao()
        val semester = dao.activeSemester() ?: return Result.success()
        val courses = dao.allCourses().associateBy { it.id }
        prune(ctx, System.currentTimeMillis())

        return if (inputData.getLong(KEY_START, 0L) > 0L) {
            runOneOff(ctx, settings, dao.allSchedules(), courses, semester)
        } else {
            runSweep(ctx, settings, dao.allSchedules(), courses, semester, System.currentTimeMillis())
        }
    }

    /** 精确模式：只在时间仍然对得上时提醒，课表改过 / 已经上课了就跳过 */
    private fun runOneOff(
        ctx: Context,
        settings: ClassReminderSettings,
        schedules: List<CourseSchedule>,
        courses: Map<Long, Course>,
        semester: Semester
    ): Result {
        val scheduleId = inputData.getLong(KEY_SCHEDULE, 0L)
        val start = inputData.getLong(KEY_START, 0L)
        if (scheduleId <= 0L || start <= 0L) return Result.success()

        val s = schedules.firstOrNull { it.id == scheduleId } ?: return Result.success()
        if (!s.remind) return Result.success()
        val c = courses[s.courseId] ?: return Result.success()
        if (!ClassFilter.matches(c.className, settings.className)) return Result.success()

        val week = WeekCalc.weekOf(semester, start)
        if (week < 1 || week > semester.totalWeeks) return Result.success()
        if (!WeekCalc.isWeekActive(s, week)) return Result.success()
        val expected = WeekCalc.startTimeMillis(semester, week, s.weekday, s.startTime)
            ?: return Result.success()
        if (expected != start) return Result.success()

        val now = System.currentTimeMillis()
        val trigger = start - ClassReminderLogic.leadMillis(settings.minutesBefore)
        // 排期被系统延后了：太早（说明设置改过）跳过；已经上课超过 5 分钟也别补报
        if (now < trigger - 2 * 60_000L) return Result.success()
        if (now >= start + 5 * 60_000L) return Result.success()

        if (!claim(ctx, keyOf(s.id, start), start)) return Result.success()
        val minutesUntil = ((start - now + 59_999L) / 60_000L).coerceAtLeast(0L)
        Notifications.notify(
            ctx,
            Notifications.CHANNEL_STUDY,
            ID_BASE + (s.id % 1000).toInt(),
            "上课提醒：" + c.name,
            lineFor(s, c, minutesUntil)
        )
        return Result.success()
    }

    /** 兜底扫描：补发已到点、还没开始的课；多节合并成一条 */
    private fun runSweep(
        ctx: Context,
        settings: ClassReminderSettings,
        schedules: List<CourseSchedule>,
        courses: Map<Long, Course>,
        semester: Semester,
        now: Long
    ): Result {
        val due = ClassReminderLogic.due(
            schedules = schedules,
            coursesById = courses,
            semester = semester,
            now = now,
            minutesBefore = settings.minutesBefore,
            className = settings.className
        ).filter { now >= it.startMillis - ClassReminderLogic.leadMillis(settings.minutesBefore) }
        if (due.isEmpty()) return Result.success()

        val fresh = due.filter { claim(ctx, keyOf(it.schedule.id, it.startMillis), it.startMillis) }
        if (fresh.isEmpty()) return Result.success()
        if (fresh.size == 1) {
            val d = fresh.first()
            Notifications.notify(
                ctx,
                Notifications.CHANNEL_STUDY,
                ID_BASE + (d.schedule.id % 1000).toInt(),
                "上课提醒：" + d.course.name,
                lineFor(d.schedule, d.course, d.minutesUntil)
            )
        } else {
            Notifications.notifySummary(
                ctx,
                Notifications.CHANNEL_STUDY,
                ID_SWEEP,
                "上课提醒：" + fresh.size + " 节",
                fresh.map { lineFor(it.schedule, it.course, it.minutesUntil) }
            )
        }
        return Result.success()
    }

    private fun lineFor(s: CourseSchedule, c: Course, minutesUntil: Long): String = buildString {
        append(s.startTime).append("-").append(s.endTime).append("  ").append(c.name)
        if (s.location.isNotBlank()) append("  📍").append(s.location)
        append("（约 ").append(minutesUntil).append(" 分钟后）")
    }

    /**
     * 原子地「认领」一次提醒：同一节课只允许一个 Worker 发出来。
     * 并发的一次性任务 / 兜底扫描同时跑时，靠这把锁 + 标记保证不重复。
     */
    private fun claim(ctx: Context, key: String, value: Long): Boolean {
        synchronized(LOCK) {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (sp.getLong(key, 0L) != 0L) return false
            sp.edit().putLong(key, value).commit()
        }
        return true
    }

    /** 清掉 12 小时前的去重记录 */
    private fun prune(ctx: Context, now: Long) {
        synchronized(LOCK) {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val old = sp.all.filterValues { (it as? Long ?: 0L) in 1 until (now - 12 * 3600_000L) }
            if (old.isEmpty()) return
            val e = sp.edit()
            old.keys.forEach { e.remove(it) }
            e.apply()
        }
    }

    companion object {
        const val WORK_NAME = "class_reminder"
        const val TAG_ONCE = "class_reminder_once"
        const val ID_BASE = 2100
        const val ID_SWEEP = 2999

        const val KEY_SCHEDULE = "schedule_id"
        const val KEY_START = "start_millis"

        private const val PREF = "class_reminder_fired"
        private val LOCK = Any()

        fun keyOf(scheduleId: Long, startMillis: Long): String = "s" + scheduleId + "_" + startMillis
    }
}
