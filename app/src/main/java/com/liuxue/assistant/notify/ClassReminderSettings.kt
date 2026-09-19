package com.liuxue.assistant.notify

import android.content.Context

/**
 * 上课提醒设置（按班级作用域）。存 SharedPreferences，**不碰用户数据库**，
 * 所以新增此功能不会影响已导入的课程/生词本等数据。
 */
class ClassReminderSettings(context: Context) {

    private val sp = context.getSharedPreferences("class_reminder", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** 提前多少分钟（1..240） */
    var minutesBefore: Int
        get() = sp.getInt("minutes", 15).coerceIn(1, 240)
        set(v) = sp.edit().putInt("minutes", v.coerceIn(1, 240)).apply()

    /** 作用班级：null/空 = 全部班级 */
    var className: String?
        get() = sp.getString("className", null)?.takeIf { it.isNotBlank() }
        set(v) = sp.edit().putString("className", v?.takeIf { it.isNotBlank() }).apply()

    val scopeLabel: String get() = className ?: "全部班级"
}
