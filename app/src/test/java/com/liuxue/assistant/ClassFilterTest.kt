package com.liuxue.assistant

import com.liuxue.assistant.domain.ClassFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 班级筛选匹配规则单元测试（纯 JVM，快） */
class ClassFilterTest {

    @Test
    fun 同班号整班课对所有子班可见() {
        assertTrue(ClassFilter.matches("10.3-622", "10.3-622А"))
        assertTrue(ClassFilter.matches("10.3-622", "10.3-622Б"))
        assertTrue(ClassFilter.matches("10.3-622", "10.3-622"))
    }

    @Test
    fun 子班只看到自己不能看到另一个子班() {
        assertTrue(ClassFilter.matches("10.3-622А", "10.3-622А"))
        assertFalse(ClassFilter.matches("10.3-622Б", "10.3-622А"))
        assertFalse(ClassFilter.matches("10.3-622А", "10.3-622Б"))
    }

    @Test
    fun 选整班时子班课也显示() {
        assertTrue(ClassFilter.matches("10.3-622А", "10.3-622"))
        assertTrue(ClassFilter.matches("10.3-622Б", "10.3-622"))
    }

    @Test
    fun 多班合上的课任一班级都能看到() {
        assertTrue(ClassFilter.matches("10.3-551, 10.3-552, 10.3-553", "10.3-552"))
        assertTrue(ClassFilter.matches("10.3-551，10.3-552", "10.3-551"))
        assertFalse(ClassFilter.matches("10.3-551, 10.3-552", "10.3-553"))
    }

    @Test
    fun 全员与空班级对所有班级可见() {
        assertTrue(ClassFilter.matches("", "10.3-354"))
        assertTrue(ClassFilter.matches("全校", "10.3-354"))
        assertTrue(ClassFilter.matches("поток 1", "10.3-354"))
    }

    @Test
    fun 未筛选时全部通过() {
        assertTrue(ClassFilter.matches("10.3-354", null))
        assertTrue(ClassFilter.matches("", null))
    }

    @Test
    fun 不同班号互不匹配() {
        assertFalse(ClassFilter.matches("10.3-354", "10.3-355"))
        assertFalse(ClassFilter.matches("10.3-354", "10.3-622А"))
    }

    @Test
    fun 基础工具方法() {
        assertEquals("10.3-622", ClassFilter.baseOf("10.3-622А"))
        assertEquals("10.3-622", ClassFilter.baseOf("10.3-622"))
        assertEquals("А", ClassFilter.subgroupOf("10.3-622А"))
        assertEquals("Б", ClassFilter.subgroupOf("252 б"))
        assertEquals(null, ClassFilter.subgroupOf("10.3-622"))
    }
}
