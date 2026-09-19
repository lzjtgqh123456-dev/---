package com.liuxue.assistant.domain

import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Semester
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 学期周次计算。
 *
 * 约定：**第 1 周从学期开始日期所在那一周的周一开始**。
 * 例如开始日期是周三 9/4，则第 1 周 = 9/2(周一) ~ 9/8(周日)。
 *
 * 全部为纯函数，便于单元测试覆盖（这层逻辑错了会导致整个课表都错）。
 */
object WeekCalc {

    const val MILLIS_PER_DAY = 24L * 3600 * 1000

    /** 某时刻所在天的 00:00 */
    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 该时间所在周的周一 00:00（ISO：周一为一周之始） */
    fun mondayOf(millis: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = startOfDay(millis) }
        // Calendar.MONDAY = 2 … SUNDAY = 1
        val dayOfWeek = c.get(Calendar.DAY_OF_WEEK)
        val back = when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.SUNDAY -> 6
            else -> dayOfWeek - Calendar.MONDAY
        }
        c.add(Calendar.DAY_OF_YEAR, -back)
        return c.timeInMillis
    }

    /** 星期几：1=周一 … 7=周日 */
    fun weekdayOf(millis: Long): Int {
        val dow = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    /** 某个学期里，给定时间处于第几周（1 起）。小于 1 表示还没开学，超过总周数表示已结束。 */
    fun weekOf(semester: Semester, millis: Long = System.currentTimeMillis()): Int {
        val firstMonday = mondayOf(semester.startDate)
        val diff = startOfDay(millis) - firstMonday
        return (diff / MILLIS_PER_DAY).toInt() / 7 + 1
    }

    /** 第 n 周的周一 00:00 */
    fun mondayOfWeek(semester: Semester, week: Int): Long =
        mondayOf(semester.startDate) + (week - 1).toLong() * 7 * MILLIS_PER_DAY

    /** 学期是否已结束 */
    fun isFinished(semester: Semester, millis: Long = System.currentTimeMillis()): Boolean =
        weekOf(semester, millis) > semester.totalWeeks

    /** 学期是否还没开始 */
    fun isNotStarted(semester: Semester, millis: Long = System.currentTimeMillis()): Boolean =
        weekOf(semester, millis) < 1

    /** 该周次是否落在课程安排的周范围内（含单双周） */
    fun isWeekActive(s: CourseSchedule, week: Int): Boolean {
        if (week < s.weekFrom || week > s.weekTo) return false
        return when (s.parity) {
            CourseSchedule.PARITY_ODD -> week % 2 == 1
            CourseSchedule.PARITY_EVEN -> week % 2 == 0
            else -> true
        }
    }

    /** 某周某天该上哪些课（已按开始时间排序） */
    fun lessonsOn(
        schedules: List<CourseSchedule>,
        semester: Semester,
        week: Int,
        weekday: Int
    ): List<CourseSchedule> =
        schedules
            .filter { it.weekday == weekday && isWeekActive(it, week) }
            .sortedBy { it.startTime }

    /** 今天该上的课 */
    fun todayLessons(
        schedules: List<CourseSchedule>,
        semester: Semester,
        millis: Long = System.currentTimeMillis()
    ): List<CourseSchedule> = lessonsOn(schedules, semester, weekOf(semester, millis), weekdayOf(millis))

    /** "HH:mm" -> 从当天 0 点起的分钟数；解析失败返回 -1 */
    fun minutesOf(time: String): Int {
        val parts = time.trim().split(":")
        if (parts.size < 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    /** 格式化分钟数为 "HH:mm" */
    fun formatMinutes(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)

    /** 课程开始时间的绝对时间戳（用于提醒） */
    fun startTimeMillis(
        semester: Semester,
        week: Int,
        weekday: Int,
        startTime: String
    ): Long? {
        val mins = minutesOf(startTime)
        if (mins < 0) return null
        val monday = mondayOfWeek(semester, week)
        return monday + (weekday - 1).toLong() * MILLIS_PER_DAY + mins * 60_000L
    }

    /** 距离某时刻还有多久（人类可读） */
    fun humanUntil(target: Long, now: Long = System.currentTimeMillis()): String {
        val diff = target - now
        if (diff < 0) {
            val days = -diff / MILLIS_PER_DAY
            return if (days == 0L) "已过去" else "已过去 $days 天"
        }
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
        return when {
            days > 1 -> "$days 天后"
            days == 1L -> "明天"
            hours >= 1 -> "$hours 小时后"
            else -> "即将开始"
        }
    }

    /** 周次显示文本，如"第 5 周 / 共 18 周" */
    fun weekLabel(semester: Semester, millis: Long = System.currentTimeMillis()): String {
        val w = weekOf(semester, millis)
        return when {
            w < 1 -> "未开学（开学后第 1 周）"
            w > semester.totalWeeks -> "学期已结束"
            else -> "第 $w 周 / 共 ${semester.totalWeeks} 周"
        }
    }
}
