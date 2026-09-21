package com.liuxue.assistant.domain

import com.liuxue.assistant.data.study.Course
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Semester
import java.util.Calendar

/**
 * 「上课前 N 分钟提醒」的纯逻辑（便于单元测试）。
 *
 * 两种用法：
 *  - [due]：兜底扫描用 —— 挑出今天该上、属于指定班级、且距离开始时间在 [now, now+N] 之间的课；
 *  - [upcoming]：精确排期用 —— 算出未来一段时间里每节课「上课时间 - 提前 N 分钟」的准确触发时刻，
 *    交给一次性任务在到点时提醒（比 15 分钟轮询准得多）。
 *
 * 两种情况都会跳过 `remind = false` 的课时。
 */
object ClassReminderLogic {

    data class Due(
        val schedule: CourseSchedule,
        val course: Course,
        val startMillis: Long,
        /** 还有几分钟开始（向上取整，至少 1） */
        val minutesUntil: Long
    )

    /** 已经排好的一次提醒：到 [triggerMillis] 就该提醒这门课 */
    data class Occurrence(
        val schedule: CourseSchedule,
        val course: Course,
        val startMillis: Long,
        val triggerMillis: Long
    )

    /** 提前量（毫秒），限制在 1..240 分钟 */
    fun leadMillis(minutesBefore: Int): Long = minutesBefore.coerceIn(1, 240) * 60_000L

    /** 今天该上、在 [now, now+N] 内开始的课（兜底扫描用；只补发，不会提前） */
    fun due(
        schedules: List<CourseSchedule>,
        coursesById: Map<Long, Course>,
        semester: Semester,
        now: Long = System.currentTimeMillis(),
        minutesBefore: Int,
        className: String?
    ): List<Due> {
        val lead = leadMillis(minutesBefore)
        val week = WeekCalc.weekOf(semester, now)
        if (week < 1 || week > semester.totalWeeks) return emptyList()
        return WeekCalc.todayLessons(schedules, semester, now)
            .mapNotNull { s ->
                if (!s.remind) return@mapNotNull null
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

    /**
     * 未来 [horizonMillis] 内所有「该提醒」的课，连同各自的准确触发时刻。
     * 触发时刻 = 上课时间 - 提前分钟数；只返回 `now` 之后、[horizonMillis] 之内的。
     */
    fun upcoming(
        schedules: List<CourseSchedule>,
        coursesById: Map<Long, Course>,
        semester: Semester,
        now: Long = System.currentTimeMillis(),
        minutesBefore: Int,
        className: String?,
        horizonMillis: Long = 7 * WeekCalc.MILLIS_PER_DAY
    ): List<Occurrence> {
        val lead = leadMillis(minutesBefore)
        val horizonEnd = now + horizonMillis
        val days = (horizonMillis / WeekCalc.MILLIS_PER_DAY).toInt().coerceIn(1, 30)
        val out = ArrayList<Occurrence>()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        repeat(days + 1) {
            val day = cal.timeInMillis
            val week = WeekCalc.weekOf(semester, day)
            if (week in 1..semester.totalWeeks) {
                val weekday = WeekCalc.weekdayOf(day)
                schedules.forEach { s ->
                    if (s.weekday != weekday || !s.remind) return@forEach
                    if (!WeekCalc.isWeekActive(s, week)) return@forEach
                    val c = coursesById[s.courseId] ?: return@forEach
                    if (!ClassFilter.matches(c.className, className)) return@forEach
                    val start = WeekCalc.startTimeMillis(semester, week, s.weekday, s.startTime)
                        ?: return@forEach
                    val trigger = start - lead
                    if (trigger > now && trigger <= horizonEnd) {
                        out.add(Occurrence(s, c, start, trigger))
                    }
                }
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out.sortedBy { it.triggerMillis }
    }
}
