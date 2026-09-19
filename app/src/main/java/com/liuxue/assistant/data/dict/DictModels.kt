package com.liuxue.assistant.data.dict

/**
 * 词典领域模型（非 Room 实体）。
 * 内置词典通过 DictStore 用原生 SQL 读取。
 */

/** 词条 */
data class DictEntry(
    val id: Long,
    val lemma: String,
    val lemmaPlain: String,
    val pos: String?,
    val ipa: String?,
    val glossZh: String?,
    val glossEn: String?,
    /** lemma = 原形；form = 变形条目 */
    val kind: String? = null
)

/** 变格/变位表中的一行 */
data class DictForm(
    val rowid: Long,
    val entryId: Long,
    val form: String,
    val formPlain: String,
    val tags: String?
)

/** 查词命中结果 */
data class LookupHit(
    val entryId: Long,
    val lemma: String,
    val pos: String?,
    val ipa: String?,
    val glossZh: String?,
    val glossEn: String?,
    val matchedTag: String?,
    val isLemma: Int
)
