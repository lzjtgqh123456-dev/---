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
            ORDER BY LENGTH(e.lemma), e.id
            LIMIT $limit
        """.trimIndent()
        return db().rawQuery(sql, arrayOf(prefix)).use { c -> c.toHits() }
    }

    /**
     * 按释义反查：同时匹配中文释义与英文释义。
     * 俄汉包可输入中文/英文反查俄语；英汉包可输入中文反查英文。
     */
    fun searchChinese(q: String, glossColumn: String = "glossZh", limit: Int = 40): List<LookupHit> {
        // 按当前词典入口的释义语言反查：俄汉=中文、俄英=英文、英汉=中文
        val col = if (glossColumn == "glossEn") "glossEn" else "glossZh"
        val sql = """
            SELECT e.id AS entryId, e.lemma, e.pos, e.ipa, e.glossZh, e.glossEn,
                   '' AS matchedTag, 1 AS isLemma
            FROM dict_entry e
            WHERE e.$col LIKE '%' || ? || '%'
            ORDER BY LENGTH(e.lemma)
            LIMIT $limit
        """.trimIndent()
        return db().rawQuery(sql, arrayOf(q)).use { c -> c.toHits() }
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
