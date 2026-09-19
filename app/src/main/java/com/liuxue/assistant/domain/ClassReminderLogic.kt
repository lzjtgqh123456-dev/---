package com.liuxue.assistant.domain

import com.liuxue.assistant.data.study.Course
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Semester

/**
 * 「上课前 N 分钟提醒」的纯逻辑（便于单元测试）。
 *
 * 只挑出：今天该上、属于指定班级、且距离开始时间在 [now, now+minutesBefore] 之间的课。
 */
object ClassReminderLogic {

    data class Due(
        val schedule: CourseSchedule,
        val course: Course,
        val startMillis: Long,
        /** 还有几分钟开始（向上取整，至少 1） */
        val minutesUntil: Long
    )

    fun due(
        schedules: List<CourseSchedule>,
        coursesById: Map<Long, Course>,
        semester: Semester,
        now: Long = System.currentTimeMillis(),
        minutesBefore: Int,
        className: String?
    ): List<Due> {
        val lead = minutesBefore.coerceIn(1, 240) * 60_000L
        val week = WeekCalc.weekOf(semester, now)
        if (week < 1 || week > semester.totalWeeks) return emptyList()
        return WeekCalc.todayLessons(schedules, semester, now)
            .mapNotNull { s ->
                val c = coursesById[s.courseId] ?: return@mapNotNull null
                if (!ClassFilter.matches(c.className, className)) return@mapNotNull null
                val start = WeekCalc.startTimeMillis(semester, week, s.weekday, s.startTime)
                    ?: return@mapNotNull null
                val diff = start - now
                if (diff < 0 || diff > lead) return@mapNotNull null
                Due(s, c, start, (diff + 59_999L) / 60_000L)
            }
            .sortedBy { it.startMillis }
    }
}
