package com.liuxue.assistant.domain

/**
 * 班级筛选匹配规则。
 *
 * 课表里的「班级」有几种写法，直接字符串相等会漏课：
 *   - 整班课：`10.3-622`（а/б 两个子班一起上）
 *   - 子班课：`10.3-622А` / `10.3-622Б`
 *   - 多班合上：`10.3-551, 10.3-552, 10.3-553`（导入时会拆成多条，但历史数据/手工录入可能仍在一条里）
 *   - 全员/公开：`全校`、`поток`、空字符串等
 *
 * 选定某个班级时（例如 `10.3-622А`），应同时看到：
 *   - 该子班自己的课；
 *   - 同班号的整班课（`10.3-622`）；
 *   - 全员/公开课。
 * 但**不应**看到同班号另一个子班（`10.3-622Б`）的课。
 */
object ClassFilter {

    private val UNIVERSAL = listOf(
        "全校", "全部", "所有", "全体", "公共", "未分班", "不分班", "无班级",
        "поток", "потоков", "потоковая", "все", "общ", "не указан"
    )

    fun matches(courseClass: String, filter: String?): Boolean {
        val f = filter?.trim().orEmpty()
        if (f.isEmpty()) return true                  // 未筛选 = 全部
        val c = courseClass.trim()
        if (c.isEmpty()) return true                  // 没有班级 = 通用课，任何班级都该看到
        if (isUniversal(c)) return true
        val parts = c.split(',', '，', '、', ';', '；', '/')
            .map { it.trim() }.filter { it.isNotEmpty() }
        val fBase = baseOf(f)
        val fSub = subgroupOf(f)
        for (p in parts) {
            if (p.equals(f, ignoreCase = true)) return true
            if (!baseOf(p).equals(fBase, ignoreCase = true)) continue
            val pSub = subgroupOf(p)
            // 同一个班号：整班课任何子班都能看；选整班时子班课也能看；子班对子班必须一致
            if (pSub == null) return true
            if (fSub == null) return true
            if (pSub.equals(fSub, ignoreCase = true)) return true
        }
        return false
    }

    fun isUniversal(cls: String): Boolean {
        val s = cls.lowercase()
        return UNIVERSAL.any { s.contains(it) }
    }

    /** `10.3-622А` -> `10.3-622`；无子班后缀则原样返回 */
    fun baseOf(cls: String): String {
        val t = cls.trim()
        if (isSubgroup(t)) return t.dropLast(1).trimEnd()
        return t
    }

    /** 末尾子班字母（А/Б/а/б/A/B），无则 null；返回统一大写 */
    fun subgroupOf(cls: String): String? {
        val t = cls.trim()
        return if (isSubgroup(t)) t.last().uppercase() else null
    }

    /** 末位是 А/Б 且前一个非空格字符是数字（如 `10.3-622А`、`252 а`） */
    private fun isSubgroup(t: String): Boolean {
        if (t.length < 2) return false
        val last = t.last()
        if (last !in "АБабAB") return false
        val rest = t.dropLast(1).trimEnd()
        return rest.isNotEmpty() && rest.last().isDigit()
    }
}
