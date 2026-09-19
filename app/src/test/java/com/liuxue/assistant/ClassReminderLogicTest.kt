package com.liuxue.assistant

import com.liuxue.assistant.data.study.Course
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Semester
import com.liuxue.assistant.domain.ClassReminderLogic
import com.liuxue.assistant.domain.WeekCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 上课提醒「提前 N 分钟」纯逻辑单元测试 */
class ClassReminderLogicTest {

    private fun nowMinutes(now: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    @Test
    fun 命中即将开始的课并给出剩余分钟() {
        val now = System.currentTimeMillis()
        val sem = Semester(name = "测试学期", startDate = WeekCalc.mondayOf(now), totalWeeks = 18)
        val start = WeekCalc.formatMinutes((nowMinutes(now) + 10) % (24 * 60))
        val s = CourseSchedule(
            id = 1L, courseId = 7L, weekday = WeekCalc.weekdayOf(now),
            startTime = start, endTime = "23:59", location = "216 РЛиМП"
        )
        val c = Course(id = 7L, name = "高级英语", className = "10.3-622А")
        val due = ClassReminderLogic.due(
            listOf(s), mapOf(7L to c), sem, now, minutesBefore = 15, className = "10.3-622А"
        )
        assertEquals(1, due.size)
        assertTrue("剩余分钟应在 1..15", due.first().minutesUntil in 1..15)
        assertEquals("216 РЛиМП", due.first().schedule.location)
    }

    @Test
    fun 提前时间不够时不提醒() {
        val now = System.currentTimeMillis()
        val sem = Semester(name = "测试学期", startDate = WeekCalc.mondayOf(now), totalWeeks = 18)
        val start = WeekCalc.formatMinutes((nowMinutes(now) + 10) % (24 * 60))
        val s = CourseSchedule(id = 1L, courseId = 7L, weekday = WeekCalc.weekdayOf(now),
            startTime = start, endTime = "23:59")
        val c = Course(id = 7L, name = "高级英语", className = "10.3-622А")
        val due = ClassReminderLogic.due(listOf(s), mapOf(7L to c), sem, now,
            minutesBefore = 5, className = "10.3-622А")
        assertTrue("只提前 5 分钟不应命中 10 分钟后的课", due.isEmpty())
    }

    @Test
    fun 班级不匹配不提醒() {
        val now = System.currentTimeMillis()
        val sem = Semester(name = "测试学期", startDate = WeekCalc.mondayOf(now), totalWeeks = 18)
        val start = WeekCalc.formatMinutes((nowMinutes(now) + 10) % (24 * 60))
        val s = CourseSchedule(id = 1L, courseId = 7L, weekday = WeekCalc.weekdayOf(now),
            startTime = start, endTime = "23:59")
        val c = Course(id = 7L, name = "高级英语", className = "10.3-622А")
        assertTrue("选 Б 班不应收到 А 班的提醒",
            ClassReminderLogic.due(listOf(s), mapOf(7L to c), sem, now, 15, "10.3-622Б").isEmpty())
        assertEquals("选整班应收到子班的提醒", 1,
            ClassReminderLogic.due(listOf(s), mapOf(7L to c), sem, now, 15, "10.3-622").size)
    }
}
