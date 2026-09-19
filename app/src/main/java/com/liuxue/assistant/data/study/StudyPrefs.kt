package com.liuxue.assistant.data.study

import android.content.Context

/**
 * 学业页的轻量设置（存 SharedPreferences，**不动数据库**）。
 *
 * 需求：用户选定班级后，重启 App / 切页 / 重新导入课表都不应该改动这个选择，
 * 只有用户自己再改才变——所以把 classFilter 落盘。
 */
class StudyPrefs(context: Context) {

    private val sp = context.getSharedPreferences("study_prefs", Context.MODE_PRIVATE)

    /** 班级筛选：null/空 = 全部班级 */
    var classFilter: String?
        get() = sp.getString("classFilter", null)?.takeIf { it.isNotBlank() }
        set(v) = sp.edit().putString("classFilter", v?.takeIf { it.isNotBlank() }).apply()
}
