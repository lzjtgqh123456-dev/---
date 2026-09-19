package com.liuxue.assistant

import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Semester
import com.liuxue.assistant.domain.WeekCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 学期周次计算测试。
 * 这层算错会导致整个课表、提醒全错，所以必须严格验证。
 */
class WeekCalcTest {

    /** 构造一个指定年月日的当天 00:00 时间戳 */
    private fun at(y: Int, m: Int, d: Int): Long = Calendar.getInstance().apply {
        clear(); set(y, m, d)
    }.timeInMillis

    private fun semester(startY: Int, startM: Int, startD: Int, weeks: Int = 18) =
        Semester(id = 1, name = "测试学期", startDate = at(startY, startM, startD), totalWeeks = weeks)

    @Test
    fun `周一到周日的编号正确`() {
        // 2026-03-02 是周一
        assertEquals(1, WeekCalc.weekdayOf(at(2026, Calendar.MARCH, 2)))
        assertEquals(2, WeekCalc.weekdayOf(at(2026, Calendar.MARCH, 3)))
        assertEquals(3, WeekCalc.weekdayOf(at(2026, Calendar.MARCH, 4)))
        assertEquals(4, WeekCalc.weekdayOf(at(2026, Calendar.MARCH, 5)))
        assertEquals(5, WeekCalc.weekdayOf(at(2026, Calendar.MARCH, 6)))
        assertEquals(6, WeekCalc.weekdayOf(at(2026, Calendar.MARCH, 7)))
        assertEquals(7, WeekCalc.weekdayOf(at(2026, Calendar.MARCH, 8)))
    }

    @Test
    fun `周一计算把整周归到同一个周一`() {
        val monday = at(2026, Calendar.MARCH, 2)
        // 该周的每一天，mondayOf 都应返回同一个周一
        for (i in 0..6) {
            assertEquals("第 $i 天", monday, WeekCalc.mondayOf(monday + i * WeekCalc.MILLIS_PER_DAY))
        }
    }

    @Test
    fun `开始日当天算第1周`() {
        val s = semester(2026, Calendar.MARCH, 2)
        assertEquals(1, WeekCalc.weekOf(s, at(2026, Calendar.MARCH, 2)))
    }

    @Test
    fun `开始日所在周的周日仍算第1周`() {
        // 3/2 是周一，3/8 是周日 -> 仍是第 1 周
        val s = semester(2026, Calendar.MARCH, 2)
        assertEquals(1, WeekCalc.weekOf(s, at(2026, Calendar.MARCH, 8)))
    }

    @Test
    fun `开始日不是周一时对齐到所在周的周一`() {
        // 学期从周三 2026-03-04 开始；其所在周的周一是 3/2
        val s = semester(2026, Calendar.MARCH, 4)
        // 3/2（周一，早于开始日）与 3/8（周日）都属第 1 周
        assertEquals(1, WeekCalc.weekOf(s, at(2026, Calendar.MARCH, 2)))
        assertEquals(1, WeekCalc.weekOf(s, at(2026, Calendar.MARCH, 8)))
        // 3/9（下周一）进入第 2 周
        assertEquals(2, WeekCalc.weekOf(s, at(2026, Calendar.MARCH, 9)))
    }

    @Test
    fun `第二周与更后面周次递推正确`() {
        val s = semester(2026, Calendar.MARCH, 2)
        assertEquals(2, WeekCalc.weekOf(s, at(2026, Calendar.MARCH, 9)))
        assertEquals(3, WeekCalc.weekOf(s, at(2026, Calendar.MARCH, 16)))
        assertEquals(5, WeekCalc.weekOf(s, at(2026, Calendar.MARCH, 30)))
        assertEquals(18, WeekCalc.weekOf(s, at(2026, Calendar.JUNE, 29)))
    }

    @Test
    fun `开学前与学期结束后能被识别`() {
        val s = semester(2026, Calendar.MARCH, 2, weeks = 18)
        assertTrue("开学前", WeekCalc.weekOf(s, at(2026, Calendar.FEBRUARY, 23)) < 1)
        assertTrue("开学前", WeekCalc.isNotStarted(s, at(2026, Calendar.FEBRUARY, 23)))
        // 第 18 周是 6/29 ~ 7/5，7/6 起超出
        assertTrue("已结束", WeekCalc.isFinished(s, at(2026, Calendar.JULY, 6)))
        assertFalse("未结束", WeekCalc.isFinished(s, at(2026, Calendar.JULY, 5)))
    }

    @Test
    fun `单双周过滤生效`() {
        val every = CourseSchedule(id = 1, courseId = 1, weekday = 1, startTime = "08:00", endTime = "09:40")
        val odd = every.copy(id = 2, parity = CourseSchedule.PARITY_ODD)
        val even = every.copy(id = 3, parity = CourseSchedule.PARITY_EVEN)

        assertTrue(WeekCalc.isWeekActive(every, 4))
        assertTrue("第4周是双周", WeekCalc.isWeekActive(even, 4))
        assertFalse("第4周不是单周", WeekCalc.isWeekActive(odd, 4))
        assertTrue("第5周是单周", WeekCalc.isWeekActive(odd, 5))
        assertFalse(WeekCalc.isWeekActive(even, 5))
    }

    @Test
    fun `周范围过滤生效`() {
        val s = CourseSchedule(
            id = 1, courseId = 1, weekday = 1,
            startTime = "10:00", endTime = "11:40",
            weekFrom = 3, weekTo = 10
        )
        assertFalse(WeekCalc.isWeekActive(s, 2))
        assertTrue(WeekCalc.isWeekActive(s, 3))
        assertTrue(WeekCalc.isWeekActive(s, 10))
        assertFalse(WeekCalc.isWeekActive(s, 11))
    }

    @Test
    fun `某天课程按开始时间排序`() {
        val sem = semester(2026, Calendar.MARCH, 2)
        val schedules = listOf(
            CourseSchedule(id = 1, courseId = 1, weekday = 1, startTime = "14:00", endTime = "15:40"),
            CourseSchedule(id = 2, courseId = 2, weekday = 1, startTime = "08:00", endTime = "09:40"),
            CourseSchedule(id = 3, courseId = 3, weekday = 1, startTime = "10:00", endTime = "11:40"),
            CourseSchedule(id = 4, courseId = 4, weekday = 2, startTime = "07:00", endTime = "08:00")
        )
        val monday = WeekCalc.lessonsOn(schedules, sem, 1, 1)
        assertEquals(listOf("08:00", "10:00", "14:00"), monday.map { it.startTime })
    }

    @Test
    fun `今天课程查询只返回匹配周次与星期`() {
        val sem = semester(2026, Calendar.MARCH, 2)
        val schedules = listOf(
            // 第1周周一有课
            CourseSchedule(id = 1, courseId = 1, weekday = 1, startTime = "09:00", endTime = "10:00"),
            // 只在 5-10 周
            CourseSchedule(id = 2, courseId = 2, weekday = 1, startTime = "11:00", endTime = "12:00", weekFrom = 5, weekTo = 10),
            // 双周才有
            CourseSchedule(id = 3, courseId = 3, weekday = 1, startTime = "13:00", endTime = "14:00", parity = CourseSchedule.PARITY_EVEN)
        )
        // 3/2 是第 1 周周一（单周）
        val day1 = WeekCalc.todayLessons(schedules, sem, at(2026, Calendar.MARCH, 2))
        assertEquals(listOf(1L), day1.map { it.id })

        // 3/9 是第 2 周周一（双周）-> 课程 1 与课程 3
        val day2 = WeekCalc.todayLessons(schedules, sem, at(2026, Calendar.MARCH, 9))
        assertEquals(listOf(1L, 3L), day2.map { it.id })

        // 3/30 是第 5 周周一 -> 课程 1、2，且为单周不含 3
        val day5 = WeekCalc.todayLessons(schedules, sem, at(2026, Calendar.MARCH, 30))
        assertEquals(listOf(1L, 2L), day5.map { it.id })
    }

    @Test
    fun `时间解析与格式化互逆`() {
        assertEquals(0, WeekCalc.minutesOf("00:00"))
        assertEquals(480, WeekCalc.minutesOf("08:00"))
        assertEquals(14 * 60 + 5, WeekCalc.minutesOf("14:05"))
        assertEquals(-1, WeekCalc.minutesOf("bad"))
        assertEquals(-1, WeekCalc.minutesOf("25:00"))
        assertEquals("08:00", WeekCalc.formatMinutes(480))
        assertEquals("00:05", WeekCalc.formatMinutes(5))
    }

    @Test
    fun `课程开始的绝对时间计算正确`() {
        val sem = semester(2026, Calendar.MARCH, 2)
        // 第 1 周周三（3/4）08:00
        val t = WeekCalc.startTimeMillis(sem, 1, 3, "08:00")!!
        val expect = at(2026, Calendar.MARCH, 4) + 8 * 3600_000L
        assertEquals(expect, t)
        // 第 3 周周一（3/16）14:30
        val t3 = WeekCalc.startTimeMillis(sem, 3, 1, "14:30")!!
        assertEquals(at(2026, Calendar.MARCH, 16) + (14 * 60 + 30) * 60_000L, t3)
        // 非法时间返回 null
        assertEquals(null, WeekCalc.startTimeMillis(sem, 1, 1, "xx"))
    }

    @Test
    fun `剩余时间文案合理`() {
        val now = at(2026, Calendar.MARCH, 2) + 10 * 3600_000L   // 10:00
        // <1 小时：即将开始
        assertEquals("即将开始", WeekCalc.humanUntil(now + 30 * 60_000L, now))
        // 数小时
        assertTrue(WeekCalc.humanUntil(now + 3 * 3600_000L, now).contains("小时"))
        // 明天 / 数天后
        assertEquals("明天", WeekCalc.humanUntil(now + 26 * 3600_000L, now))
        assertTrue(WeekCalc.humanUntil(now + 3 * WeekCalc.MILLIS_PER_DAY, now).contains("天"))
        // 已过去
        assertTrue(WeekCalc.humanUntil(now - WeekCalc.MILLIS_PER_DAY, now).contains("已过去"))
    }

    @Test
    fun `周次文案覆盖未开学与已结束`() {
        val s = semester(2026, Calendar.MARCH, 2, weeks = 18)
        assertTrue(WeekCalc.weekLabel(s, at(2026, Calendar.FEBRUARY, 1)).contains("未开学"))
        assertTrue(WeekCalc.weekLabel(s, at(2026, Calendar.MARCH, 9)).contains("第 2 周"))
        assertTrue(WeekCalc.weekLabel(s, at(2026, Calendar.AUGUST, 1)).contains("已结束"))
    }
}
