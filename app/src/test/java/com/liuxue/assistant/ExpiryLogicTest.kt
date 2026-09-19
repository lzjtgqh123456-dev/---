package com.liuxue.assistant

import com.liuxue.assistant.domain.ExpiryLevel
import com.liuxue.assistant.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 到期提醒的核心时间逻辑测试。
 * 这部分逻辑直接决定"什么时候提醒用户"，必须准确，所以用单元测试锁住行为。
 */
class ExpiryLogicTest {

    private fun daysFromToday(days: Int): Long =
        DateUtils.todayStart() + days.toLong() * 24 * 3600 * 1000

    @Test
    fun `无到期日时等级为NONE`() {
        assertEquals(ExpiryLevel.NONE, ExpiryLevel.of(null, 30))
    }

    @Test
    fun `已过期判定`() {
        assertEquals(ExpiryLevel.EXPIRED, ExpiryLevel.of(daysFromToday(-1), 30))
        assertEquals(ExpiryLevel.EXPIRED, ExpiryLevel.of(daysFromToday(-100), 30))
    }

    @Test
    fun `七天内为紧急`() {
        assertEquals(ExpiryLevel.URGENT, ExpiryLevel.of(daysFromToday(0), 30))
        assertEquals(ExpiryLevel.URGENT, ExpiryLevel.of(daysFromToday(1), 30))
        assertEquals(ExpiryLevel.URGENT, ExpiryLevel.of(daysFromToday(7), 30))
    }

    @Test
    fun `提醒窗口内为临近`() {
        assertEquals(ExpiryLevel.WARNING, ExpiryLevel.of(daysFromToday(8), 30))
        assertEquals(ExpiryLevel.WARNING, ExpiryLevel.of(daysFromToday(30), 30))
    }

    @Test
    fun `超出提醒窗口为有效`() {
        assertEquals(ExpiryLevel.SAFE, ExpiryLevel.of(daysFromToday(31), 30))
        assertEquals(ExpiryLevel.SAFE, ExpiryLevel.of(daysFromToday(365), 30))
    }

    @Test
    fun `自定义提醒天数生效`() {
        // 提前 90 天提醒：第 60 天应落在 WARNING 而不是 SAFE
        assertEquals(ExpiryLevel.WARNING, ExpiryLevel.of(daysFromToday(60), 90))
        assertEquals(ExpiryLevel.SAFE, ExpiryLevel.of(daysFromToday(91), 90))
    }

    @Test
    fun `不提醒时不推送通知`() {
        assertFalse(ExpiryLevel.shouldNotify(daysFromToday(1), -1))
        assertFalse(ExpiryLevel.shouldNotify(null, 30))
    }

    @Test
    fun `已过期也会提醒`() {
        assertTrue(ExpiryLevel.shouldNotify(daysFromToday(-5), 30))
    }

    @Test
    fun `临界点在提醒范围内`() {
        assertTrue(ExpiryLevel.shouldNotify(daysFromToday(30), 30))
        assertFalse(ExpiryLevel.shouldNotify(daysFromToday(31), 30))
    }

    @Test
    fun `剩余天数为负数时提示已过期`() {
        assertTrue(DateUtils.humanRemaining(daysFromToday(-3)).contains("已过期"))
        assertTrue(DateUtils.humanRemaining(daysFromToday(-3)).contains("3"))
    }

    @Test
    fun `今天到期有专门文案`() {
        assertEquals("今天到期", DateUtils.humanRemaining(daysFromToday(0)))
        assertEquals("明天到期", DateUtils.humanRemaining(daysFromToday(1)))
    }

    @Test
    fun `日期格式化稳定`() {
        val millis = DateUtils.toMillis(2026, Calendar.MARCH, 15)
        assertEquals("2026-03-15", DateUtils.formatDay(millis))
    }

    @Test
    fun `到期日与签发日边界比较`() {
        val issue = DateUtils.toMillis(2026, Calendar.JANUARY, 1)
        val expire = DateUtils.toMillis(2026, Calendar.JANUARY, 2)
        assertTrue(expire > issue)
    }
}
