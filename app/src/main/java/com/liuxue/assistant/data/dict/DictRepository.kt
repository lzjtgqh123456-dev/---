package com.liuxue.assistant.data.dict

import android.content.Context
import com.liuxue.assistant.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 查词引擎。
 *
 * 核心能力：用户输入**任意词形**（变格/变位后的形式），都能定位到原形并给出该形所对应的语法信息。
 * 例如输入 "собаками" -> собака（instrumental, plural，工具格复数）。
 *
 * 查词统一先去重音再匹配，所以用户不需要输入重音符号也能查到。
 */
class DictRepository(
    private val context: Context,
    private val appDb: AppDatabase
) {
    private val packFlow = MutableStateFlow(DictAssetDb.RU_ZH)

    /** 当前词典包（UI 可切换：俄汉·俄英 / 英汉） */
    val currentPack: StateFlow<DictAssetDb.Pack> = packFlow.asStateFlow()

    /** 全部可用词典包 */
    fun packs(): List<DictAssetDb.Pack> = DictAssetDb.ALL

    /** 切换词典包（切换前先把该包释放好，避免首次查词卡顿） */
    suspend fun setPack(pack: DictAssetDb.Pack) = withContext(Dispatchers.IO) {
        DictAssetDb.open(context, pack)
        packFlow.value = pack
    }

    private fun store(): DictStore = DictStore(context, packFlow.value)

    private val wordbookDao = appDb.wordbookDao()
    private val phraseDao = appDb.phraseDao()
    private val historyDao = appDb.historyDao()

    companion object {
        private const val COMBINING_ACUTE = "\u0301"

        /** 去掉俄语重音符号（组合尖音符），便于无重音输入匹配 */
        fun stripStress(s: String): String = s.replace(COMBINING_ACUTE, "")
    }

    // ---------- 查词 ----------

    /** 主查词入口：精确匹配（含变形） */
    suspend fun lookup(query: String): List<LookupHit> = withContext(Dispatchers.IO) {
        val q = stripStress(query.trim())
        if (q.isEmpty()) return@withContext emptyList()
        val hits = store().lookup(q)
        if (hits.isNotEmpty()) {
            historyDao.add(SearchHistory(query = query.trim(), entryId = hits.first().entryId))
        }
        hits
    }

    /** 输入联想 */
    suspend fun suggest(prefix: String): List<LookupHit> = withContext(Dispatchers.IO) {
        val p = stripStress(prefix.trim())
        if (p.isEmpty()) emptyList() else store().suggest(p)
    }

    /** 反查：按当前词典入口的释义语言（俄汉/英汉=中文，俄英=英文） */
    suspend fun searchChinese(q: String): List<LookupHit> = withContext(Dispatchers.IO) {
        if (q.isBlank()) emptyList()
        else store().searchChinese(q.trim(), packFlow.value.glossColumn)
    }

    /** 模糊包含（俄文） */
    suspend fun contains(q: String): List<LookupHit> = withContext(Dispatchers.IO) {
        val s = stripStress(q.trim())
        if (s.length < 2) emptyList() else store().contains(s)
    }

    /** 词条详情 */
    suspend fun entry(id: Long): DictEntry? = withContext(Dispatchers.IO) { store().entry(id) }

    /** 变格/变位表 */
    suspend fun forms(entryId: Long): List<DictForm> = withContext(Dispatchers.IO) {
        store().forms(entryId)
    }

    /** 把变形表按语法维度分组，便于 UI 展示成表格 */
    suspend fun declensionTable(entryId: Long): Map<String, List<DictForm>> =
        withContext(Dispatchers.IO) {
            store().forms(entryId)
                .filter { it.formPlain.isNotBlank() && it.form != "no-table-tags" }
                .groupBy { f ->
                    when {
                        f.tags == null -> "其他"
                        f.tags.contains("case") || CASE_TAGS.any { t -> f.tags!!.contains(t) } -> "格变化"
                        f.tags.contains("plural") -> "复数"
                        f.tags.contains("tense") || f.tags.contains("person") -> "变位"
                        f.tags.contains("comparative") -> "比较级"
                        else -> "其他形式"
                    }
                }
        }

    suspend fun stats(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        store().entryCount() to store().translatedCount()
    }

    // ---------- 生词本 ----------

    /** 一次性取全部生词（抽查用） */
    suspend fun allWordbookItems(): List<WordbookItem> = withContext(Dispatchers.IO) {
        wordbookDao.allItems()
    }

    fun observeWordbook(categoryId: Long = -1L): Flow<List<WordbookItem>> =
        wordbookDao.observe(categoryId)

    fun observeWordbookCount(): Flow<Int> = wordbookDao.countFlow()

    fun observeWordbookCategories(): Flow<List<WordbookCategory>> =
        wordbookDao.observeCategories()

    suspend fun isInWordbook(entryId: Long): Boolean = withContext(Dispatchers.IO) {
        wordbookDao.find(entryId) != null
    }

    /** 加入生词本（重复加入不报错） */
    suspend fun addToWordbook(entryId: Long, categoryId: Long = 0L): Unit =
        withContext(Dispatchers.IO) {
            val exists = wordbookDao.find(entryId)
            if (exists == null) {
                wordbookDao.upsert(WordbookItem(entryId = entryId, categoryId = categoryId))
            } else if (exists.categoryId != categoryId) {
                wordbookDao.upsert(exists.copy(categoryId = categoryId))
            }
        }

    suspend fun removeFromWordbook(entryId: Long) = withContext(Dispatchers.IO) {
        wordbookDao.remove(entryId)
    }

    suspend fun setMastery(entryId: Long, mastery: Int) = withContext(Dispatchers.IO) {
        wordbookDao.find(entryId)?.let {
            // 简易间隔重复：掌握程度越高，下次复习间隔越长
            val days = when (mastery) { 3 -> 30L; 2 -> 7L; 1 -> 2L; else -> 1L }
            wordbookDao.upsert(
                it.copy(
                    mastery = mastery,
                    dueAt = System.currentTimeMillis() + days * 24 * 3600 * 1000
                )
            )
        }
    }

    suspend fun dueItems(limit: Int = 20): List<WordbookItem> = withContext(Dispatchers.IO) {
        wordbookDao.due(System.currentTimeMillis(), limit)
    }

    suspend fun addWordbookCategory(name: String): Long = withContext(Dispatchers.IO) {
        wordbookDao.addCategory(WordbookCategory(name = name))
    }

    suspend fun deleteWordbookCategory(id: Long) = withContext(Dispatchers.IO) {
        wordbookDao.deleteCategory(id)
    }

    // ---------- 短语集 ----------

    fun observePhrases(): Flow<List<Phrase>> = phraseDao.observeAll()

    fun observePhrasesByCategory(categoryId: Long): Flow<List<Phrase>> =
        phraseDao.observeByCategory(categoryId)

    fun observePhraseCategories(): Flow<List<PhraseCategory>> = phraseDao.observeCategories()

    suspend fun addPhrase(textRu: String, textZh: String, note: String = "", categoryId: Long = 0L) =
        withContext(Dispatchers.IO) {
            phraseDao.insert(Phrase(textRu = textRu, textZh = textZh, note = note, categoryId = categoryId))
        }

    suspend fun togglePhraseStar(id: Long, starred: Boolean) = withContext(Dispatchers.IO) {
        phraseDao.setStarred(id, starred)
    }

    suspend fun deletePhrase(id: Long) = withContext(Dispatchers.IO) { phraseDao.delete(id) }

    suspend fun addPhraseCategory(name: String): Long = withContext(Dispatchers.IO) {
        phraseDao.addCategory(PhraseCategory(name = name))
    }

    suspend fun deletePhraseCategory(id: Long) = withContext(Dispatchers.IO) {
        phraseDao.deleteCategory(id)
    }

    // ---------- 历史 ----------

    fun observeHistory(): Flow<List<SearchHistory>> = historyDao.observe()

    suspend fun clearHistory() = withContext(Dispatchers.IO) { historyDao.clear() }
}

/** 用作"是否格变化"的判定 */
private val CASE_TAGS = listOf("genitive", "accusative", "dative", "instrumental", "prepositional", "nominative")
