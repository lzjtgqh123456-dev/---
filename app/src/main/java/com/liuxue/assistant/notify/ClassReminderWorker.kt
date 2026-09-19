package com.liuxue.assistant.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.domain.ClassReminderLogic

/**
 * 上课提醒 Worker：每 15 分钟检查一次，命中「今天该上、属于所选班级、N 分钟内开始」的课就发通知。
 *
 * 用 15 分钟周期任务而不是精确闹钟：不需要 SCHEDULE_EXACT_ALARM 权限（审核敏感、用户易拒），
 * 也省电。同一节课用 `课表 id + 开始时间` 去重，只提醒一次。
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
        val now = System.currentTimeMillis()
        val due = ClassReminderLogic.due(
            schedules = dao.allSchedules(),
            coursesById = courses,
            semester = semester,
            now = now,
            minutesBefore = settings.minutesBefore,
            className = settings.className
        )

        val fired = ctx.getSharedPreferences("class_reminder_fired", Context.MODE_PRIVATE)
        prune(fired, now)
        for (d in due) {
            val key = "s" + d.schedule.id + "_" + d.startMillis
            if (fired.getLong(key, 0L) != 0L) continue
            val text = buildString {
                append(d.schedule.startTime).append("-").append(d.schedule.endTime).append("  ")
                append(d.course.name)
                if (d.schedule.location.isNotBlank()) append("  📍").append(d.schedule.location)
                append("（约 ").append(d.minutesUntil).append(" 分钟后）")
            }
            Notifications.notify(
                ctx,
                Notifications.CHANNEL_STUDY,
                ID_BASE + (d.schedule.id % 1000).toInt(),
                "上课提醒：" + d.course.name,
                text
            )
            fired.edit().putLong(key, d.startMillis).apply()
        }
        return Result.success()
    }

    /** 清掉 12 小时前的去重记录 */
    private fun prune(sp: SharedPreferences, now: Long) {
        val old = sp.all.filterValues { (it as? Long ?: 0L) in 1 until (now - 12 * 3600_000L) }
        if (old.isEmpty()) return
        val e = sp.edit()
        old.keys.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        const val WORK_NAME = "class_reminder"
        const val ID_BASE = 2100
    }
}
