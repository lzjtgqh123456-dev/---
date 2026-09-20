package com.liuxue.assistant.data.dict

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * 内置词典的查询实现（原生 SQL，只读）。
 *
 * 之所以不用 Room：内置库是离线生成的数据包，Room 对预置库做严格 schema/身份哈希校验，
 * 手工生成的数据包难以满足；直接 SQL 查询更简单、更快，也不会有迁移负担。
 *
 * [pack] 决定查哪个词典包；各包表结构一致（dict_entry / dict_lookup / dict_form）。
 */
class DictStore(
    private val context: Context,
    private val pack: DictAssetDb.Pack = DictAssetDb.RU_ZH
) {

    private fun db(): SQLiteDatabase = DictAssetDb.open(context, pack)

    fun entryCount(): Int = scalar("SELECT COUNT(*) FROM dict_entry")

    fun translatedCount(): Int =
        scalar("SELECT COUNT(*) FROM dict_entry WHERE glossZh IS NOT NULL AND glossZh != ''")

    private fun scalar(sql: String): Int =
        db().rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 精确查词：任意变形都能命中，原形优先 */
    fun lookup(plain: String, limit: Int = 12): List<LookupHit> {
        val sql = """
            SELECT e.id AS entryId, e.lemma, e.pos, e.ipa, e.glossZh, e.glossEn,
                   l.tags AS matchedTag, l.isLemma AS isLemma
            FROM dict_lookup l JOIN dict_entry e ON e.id = l.entryId
            WHERE l.plain = ?
            ORDER BY l.isLemma DESC,
                     CASE WHEN e.glossZh IS NULL OR e.glossZh = '' THEN 1 ELSE 0 END,
                     e.id
            LIMIT $limit
        """.trimIndent()
        return db().rawQuery(sql, arrayOf(plain)).use { c -> c.toHits() }
    }

    /** 前缀联想 */
    fun suggest(prefix: String, limit: Int = 20): List<LookupHit> {
        val sql = """
            SELECT e.id AS entryId, e.lemma, e.pos, e.ipa, e.glossZh, e.glossEn,
                   '' AS matchedTag, 1 AS isLemma
            FROM dict_entry e
            WHERE e.lemmaPlain LIKE ? || '%'
            -- 打完整词时，它自己排最前
            ORDER BY CASE WHEN e.lemmaPlain = ? THEN 0 ELSE 1 END, LENGTH(e.lemma), e.id
            LIMIT $limit
        """.trimIndent()
        return db().rawQuery(sql, arrayOf(prefix, prefix)).use { c -> c.toHits() }
    }

    /**
     * 按释义反查：同时匹配中文释义与英文释义。
     * 俄汉包可输入中文/英文反查俄语；英汉包可输入中文反查英文。
     */
    fun searchChinese(q: String, glossColumn: String = "glossZh", limit: Int = 40): List<LookupHit> {
        // 按当前词典入口的释义语言反查：俄汉=中文、俄英=英文、英汉=中文
        val col = if (glossColumn == "glossEn") "glossEn" else "glossZh"
        // 先按"词短优先"捞一批候选，再在 Kotlin 里按义项精确排序。
        // 为什么排序不放在 SQL：释义是多义项字符串（例："书，书籍；账簿"），
        // SQL 的 e.glossZh = '书' 几乎永远不成立，根本无法表达"完全匹配"。
        val fetch = (limit * 5).coerceAtLeast(60).coerceAtMost(300)
        val sql = """
            SELECT e.id AS entryId, e.lemma, e.pos, e.ipa, e.glossZh, e.glossEn,
                   '' AS matchedTag, 1 AS isLemma
            FROM dict_entry e
            WHERE e.$col LIKE '%' || ? || '%'
            ORDER BY LENGTH(e.lemma), e.id
            LIMIT $fetch
        """.trimIndent()
        val hits = db().rawQuery(sql, arrayOf(q)).use { c -> c.toHits() }
        val target = q.trim()
        return hits
            .sortedWith(
                compareBy<LookupHit> { senseRank(it, col, target) }
                    .thenBy { it.lemma.length }
                    .thenBy { it.entryId }
            )
            .take(limit)
    }

    /**
     * 输入与这条释义的匹配程度：越小越"完全匹配"。
     * 0 = 某个义项与输入完全相同（如输入"书"，释义"书，书籍"里的"书"）
     * 1 = 释义以输入开头 / 某个义项以输入开头（主释义就是它）
     * 2 = 只是包含（一般匹配）
     * 3 = 释义为空（兜底）
     */
    private fun senseRank(h: LookupHit, col: String, q: String): Int {
        val raw = (if (col == "glossEn") h.glossEn else h.glossZh)?.trim()
        if (raw.isNullOrEmpty()) return 3
        if (raw == q) return 0
        // 先去掉 <正, 书, 尤英> / [计] / [经] 这类语体·学科标注 —— 它们是标注不是词义，
        // 不去掉的话「viz = adv. <正, 书, 尤英>即, 就是」会被误判成"完全匹配"（实测踩过）。
        val gloss = stripLabelParts(raw)
        val senses = gloss.split('，', ',', '；', ';', '、', '/', '|', '·')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (senses.any { it == q }) return 0
        if (gloss.startsWith(q) || senses.any { it.startsWith(q) }) return 1
        return 2
    }

    /** 去掉 <…> 与 […] 包裹的内容（含嵌套），只用剩下的词义文本参与匹配 */
    private fun stripLabelParts(s: String): String {
        val sb = StringBuilder()
        var depth = 0
        for (ch in s) {
            when (ch) {
                '<', '[' -> depth++
                '>', ']' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /** 模糊包含 */
    fun contains(q: String, limit: Int = 40): List<LookupHit> {
        val sql = """
            SELECT e.id AS entryId, e.lemma, e.pos, e.ipa, e.glossZh, e.glossEn,
                   '' AS matchedTag, 1 AS isLemma
            FROM dict_entry e
            WHERE e.lemmaPlain LIKE '%' || ? || '%'
            ORDER BY e.id LIMIT $limit
        """.trimIndent()
        return db().rawQuery(sql, arrayOf(q)).use { c -> c.toHits() }
    }

    fun entry(id: Long): DictEntry? {
        val sql = "SELECT id, lemma, lemmaPlain, pos, ipa, glossZh, glossEn, kind FROM dict_entry WHERE id = ?"
        return db().rawQuery(sql, arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) null
            else DictEntry(
                id = c.getLong(0),
                lemma = c.getString(1) ?: "",
                lemmaPlain = c.getString(2) ?: "",
                pos = c.getString(3),
                ipa = c.getString(4),
                glossZh = c.getString(5),
                glossEn = c.getString(6),
                kind = c.getString(7)
            )
        }
    }

    /** 变格/变位表（英语包为复数/过去式等变形） */
    fun forms(entryId: Long): List<DictForm> {
        val sql = "SELECT rowid, entryId, form, formPlain, tags FROM dict_form WHERE entryId = ? ORDER BY rowid"
        return db().rawQuery(sql, arrayOf(entryId.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DictForm(
                            rowid = c.getLong(0),
                            entryId = c.getLong(1),
                            form = c.getString(2) ?: "",
                            formPlain = c.getString(3) ?: "",
                            tags = c.getString(4)
                        )
                    )
                }
            }
        }
    }

    private fun Cursor.toHits(): List<LookupHit> = buildList {
        while (moveToNext()) {
            add(
                LookupHit(
                    entryId = getLong(0),
                    lemma = getString(1) ?: "",
                    pos = getString(2),
                    ipa = getString(3),
                    glossZh = getString(4),
                    glossEn = getString(5),
                    matchedTag = getString(6),
                    isLemma = getInt(7)
                )
            )
        }
    }
}
