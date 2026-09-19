package com.liuxue.assistant

import com.liuxue.assistant.data.study.Course
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.LessonItem
import com.liuxue.assistant.feature.study.StudyUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 学业页班级筛选的集成测试：选 а 班要能看到整班课与全员课，但看不到 б 班课 */
class StudyFilterTest {

    private fun course(id: Long, name: String, cls: String) =
        Course(id = id, name = name, className = cls)

    @Test
    fun 选子班时包含整班课与全员课但不含另一子班() {
        val courses = listOf(
            course(1, "整班课", "10.3-622"),
            course(2, "A班课", "10.3-622А"),
            course(3, "Б班课", "10.3-622Б"),
            course(4, "别的班", "10.3-354"),
            course(5, "全员课", "全校"),
            course(6, "未分班", "")
        )
        val state = StudyUiState(courses = courses, classFilter = "10.3-622А")
        val names = state.filteredCourses.map { it.name }.toSet()
        assertEquals(setOf("整班课", "A班课", "全员课", "未分班"), names)
    }

    @Test
    fun 课表筛选同样包含整班课() {
        val a = course(2, "A班课", "10.3-622А")
        val b = course(3, "Б班课", "10.3-622Б")
        val whole = course(1, "整班课", "10.3-622")
        fun lesson(id: Long, c: Course) = LessonItem(
            schedule = CourseSchedule(id = id, courseId = c.id, weekday = 1, startTime = "08:30", endTime = "10:00"),
            course = c
        )
        val state = StudyUiState(
            courses = listOf(whole, a, b),
            lessons = listOf(lesson(10, whole), lesson(11, a), lesson(12, b)),
            classFilter = "10.3-622А"
        )
        val ids = state.filteredLessons.map { it.schedule.id }.toSet()
        assertEquals(setOf(10L, 11L), ids)
    }

    @Test
    fun 未筛选时全部通过() {
        val courses = listOf(course(1, "x", "10.3-622А"), course(2, "y", "10.3-622Б"))
        assertEquals(2, StudyUiState(courses = courses, classFilter = null).filteredCourses.size)
    }
}
