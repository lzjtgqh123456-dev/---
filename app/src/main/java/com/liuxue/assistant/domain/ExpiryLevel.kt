package com.liuxue.assistant.domain

import androidx.compose.ui.graphics.Color
import com.liuxue.assistant.util.DateUtils

/** 到期紧急等级，决定颜色与是否提醒 */
enum class ExpiryLevel(val label: String, val color: Color) {
    EXPIRED("已过期", Color(0xFFD32F2F)),
    URGENT("紧急", Color(0xFFF57C00)),
    WARNING("临近", Color(0xFFFBC02D)),
    SAFE("有效", Color(0xFF388E3C)),
    NONE("无期限", Color(0xFF757575));

    companion object {
        /** @param remindDaysBefore 提前提醒天数，-1 表示不提醒 */
        fun of(expireDate: Long?, remindDaysBefore: Int): ExpiryLevel {
            if (expireDate == null) return NONE
            val days = DateUtils.daysUntil(expireDate)
            return when {
                days < 0 -> EXPIRED
                days <= 7 -> URGENT
                days <= if (remindDaysBefore > 0) remindDaysBefore else 30 -> WARNING
                else -> SAFE
            }
        }

        /** 该记录当前是否应该提醒 */
        fun shouldNotify(expireDate: Long?, remindDaysBefore: Int): Boolean {
            if (expireDate == null || remindDaysBefore < 0) return false
            val days = DateUtils.daysUntil(expireDate)
            return days <= remindDaysBefore
        }
    }
}
