package com.liuxue.assistant.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.study.Homework
import com.liuxue.assistant.domain.WeekCalc
import com.liuxue.assistant.util.DateUtils

/**
 * 学业提醒：每天早上跑一次，汇总
 *   ① 今天要上的课（含时间、地点、教师）
 *   ② 即将到期 / 已逾期的作业
 *   ③ 临近的考试
 *
 * 之所以用"每天一次汇总"而不是"每节课精确闹钟"：
 * 精确闹钟需要 SCHEDULE_EXACT_ALARM 权限（应用商店审核敏感、且用户容易拒绝），
 * 汇总式提醒无需该权限，耗电也更低。
 */
class StudyReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val dao = db.studyDao()
        val semester = dao.activeSemester() ?: return Result.success()

        val now = System.currentTimeMillis()
        val week = WeekCalc.weekOf(semester, now)
        // 学期未开始或已结束就不打扰
        if (week < 1 || week > semester.totalWeeks) return Result.success()

        val courses = dao.allCourses().associateBy { it.id }

        // ---------- ① 今天的课 ----------
        val today = WeekCalc.todayLessons(dao.allSchedules(), semester, now)
        if (today.isNotEmpty()) {
            val lines = today.map { s ->
                val c = courses[s.courseId]
                buildString {
                    append(s.startTime).append("-").append(s.endTime).append("  ")
                    append(c?.name ?: "未命名课程")
                    if (!c?.teacher.isNullOrBlank()) append("（").append(c?.teacher).append("）")
                    if (s.location.isNotBlank()) append(" @").append(s.location)
                    if (c != null && c.classType == com.liuxue.assistant.data.study.ClassType.SMALL) {
                        append(" [小班]")
                    } else if (c != null &&
                        c.classType == com.liuxue.assistant.data.study.ClassType.LECTURE) {
                        append(" [讲座]")
                    }
                }
            }
            Notifications.notifySummary(
                applicationContext,
                Notifications.CHANNEL_STUDY,
                ID_TODAY_CLASSES,
                "今天有 ${today.size} 节课",
                lines
            )
        }

        // ---------- ② 作业提醒 ----------
        val openHw = dao.openHomework()
        val dueSoon = openHw.filter { h ->
            h.dueDate != null &&
                h.dueDate!! <= now + h.remindDaysBefore.toLong() * WeekCalc.MILLIS_PER_DAY
        }
        if (dueSoon.isNotEmpty()) {
            val lines = dueSoon.sortedBy { it.dueDate }.map { h ->
                val cname = courses[h.courseId]?.name ?: "未指定课程"
                val overdue = h.dueDate!! < now
                (if (overdue) "⛔ 已逾期 " else "⏰ ") +
                    DateUtils.formatDay(h.dueDate) + "  " + cname + " · " + h.title +
                    "（" + WeekCalc.humanUntil(h.dueDate!!) + "）"
            }
            Notifications.notifySummary(
                applicationContext,
                Notifications.CHANNEL_STUDY,
                ID_HOMEWORK_DUE,
                "${dueSoon.size} 个作业待处理",
                lines
            )
        }

        // ---------- ③ 考试提醒 ----------
        val examSoon = dao.allExams().filter {
            val d = it.examDate - now
            d in 0..(it.remindDaysBefore.toLong() * WeekCalc.MILLIS_PER_DAY)
        }
        if (examSoon.isNotEmpty()) {
            val lines = examSoon.sortedBy { it.examDate }.map { e ->
                "📝 " + e.name + "  " + DateUtils.formatDateTime(e.examDate) +
                    "（" + WeekCalc.humanUntil(e.examDate) + "）" +
                    if (e.location.isNotBlank()) "  @" + e.location else ""
            }
            Notifications.notifySummary(
                applicationContext,
                Notifications.CHANNEL_STUDY,
                ID_EXAM_SOON,
                "${examSoon.size} 场考试临近",
                lines
            )
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "study_daily_check"
        const val ID_TODAY_CLASSES = 2001
        const val ID_HOMEWORK_DUE = 2002
        const val ID_EXAM_SOON = 2003
    }
}
