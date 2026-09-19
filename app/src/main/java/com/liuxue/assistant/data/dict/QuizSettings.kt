package com.liuxue.assistant.data.dict

import android.content.Context

/**
 * 每日随机抽查的设置。
 *
 * 用 SharedPreferences 而不是 DataStore：这里只有 4 个字段、读写都在设置页，
 * 同步读写更简单，也避免为一个小功能引入额外协程流。
 */
class QuizSettings(context: Context) {

    private val sp = context.getSharedPreferences("quiz_settings", Context.MODE_PRIVATE)

    /** 是否开启每日抽查 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 每次抽查几个词 */
    var dailyCount: Int
        get() = sp.getInt(KEY_COUNT, 10)
        set(v) = sp.edit().putInt(KEY_COUNT, v.coerceIn(3, 100)).apply()

    /** 每天几点提醒（24 小时制） */
    var hour: Int
        get() = sp.getInt(KEY_HOUR, 20)
        set(v) = sp.edit().putInt(KEY_HOUR, v.coerceIn(0, 23)).apply()

    var minute: Int
        get() = sp.getInt(KEY_MINUTE, 0)
        set(v) = sp.edit().putInt(KEY_MINUTE, v.coerceIn(0, 59)).apply()

    /** 只抽查未掌握的词（掌握程度 < 3） */
    var onlyUnmastered: Boolean
        get() = sp.getBoolean(KEY_ONLY_UNMASTERED, true)
        set(v) = sp.edit().putBoolean(KEY_ONLY_UNMASTERED, v).apply()

    /** 上次抽查完成的日期（yyyy-MM-dd），用于避免一天重复提醒 */
    var lastQuizDate: String?
        get() = sp.getString(KEY_LAST, null)
        set(v) = sp.edit().putString(KEY_LAST, v).apply()

    /** 昨天抽查的正确数 / 总数，用于在设置页显示进度 */
    var lastScore: String?
        get() = sp.getString(KEY_SCORE, null)
        set(v) = sp.edit().putString(KEY_SCORE, v).apply()

    fun summary(): String = "每天 $dailyCount 个词" +
        "，%02d:%02d 提醒".format(hour, minute) +
        (if (onlyUnmastered) "，仅未掌握" else "，全部生词")

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_COUNT = "count"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
        private const val KEY_ONLY_UNMASTERED = "only_unmastered"
        private const val KEY_LAST = "last_date"
        private const val KEY_SCORE = "last_score"
    }
}
