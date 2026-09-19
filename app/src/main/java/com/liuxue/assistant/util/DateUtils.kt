package com.liuxue.assistant.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object DateUtils {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatDay(millis: Long?): String = millis?.let { dayFmt.format(Date(it)) } ?: "—"
    fun formatDateTime(millis: Long): String = dateTimeFmt.format(Date(millis))

    fun toMillis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day)
        }.timeInMillis

    fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 距今天还有多少天（负数表示已过期） */
    fun daysUntil(target: Long): Int {
        val diff = target - todayStart()
        return TimeUnit.MILLISECONDS.toDays(diff).toInt()
    }

    /** 人类可读的剩余时间 */
    fun humanRemaining(target: Long): String {
        val d = daysUntil(target)
        return when {
            d < 0 -> "已过期 ${-d} 天"
            d == 0 -> "今天到期"
            d == 1 -> "明天到期"
            d < 30 -> "还有 $d 天"
            d < 365 -> "还有 ${d / 30} 个月"
            else -> "还有 ${d / 365} 年多"
        }
    }
}
