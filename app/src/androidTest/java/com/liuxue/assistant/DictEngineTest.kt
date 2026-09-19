package com.liuxue.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liuxue.assistant.data.dict.DictRepository
import com.liuxue.assistant.data.dict.DictStore
import com.liuxue.assistant.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 词典引擎真机测试。
 *
 * 直接调用数据层（不经 UI），验证：assets 释放、SQL 查询、去重音匹配、变形还原、变格表。
 * 这是"用户输入任意变格形式能否查到原形"这一核心能力的设备端验证。
 */
@RunWith(AndroidJUnit4::class)
class DictEngineTest {

    private lateinit var context: Context
    private lateinit var repo: DictRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = DictRepository(context, AppDatabase.get(context))
    }

    @Test
    fun 词典已随App释放且词条数量合理() = runBlocking {
        val (entries, translated) = repo.stats()
        println("词条数=$entries 已翻译=$translated")
        assertTrue("词条数应不少于 60000，实际=$entries", entries >= 60_000)
    }

    @Test
    fun 用变格形式可查到原形() = runBlocking {
        // собаками（工具格复数）-> собака
        val hits = repo.lookup("собаками")
        assertTrue("应查到 собаками", hits.isNotEmpty())
        val first = hits.first()
        println("собаками -> ${first.lemma} / ${first.matchedTag}")
        assertEquals("собака", first.lemma)
        assertTrue("应带工具格标签", first.matchedTag?.contains("instrumental") == true)
    }

    @Test
    fun 多个变格形式都指向同一原形() = runBlocking {
        listOf("собаки", "собаку", "собаками", "собак").forEach { form ->
            val hits = repo.lookup(form)
            assertTrue("$form 应有结果", hits.isNotEmpty())
            assertEquals("$form 应还原为 собака", "собака", hits.first().lemma)
        }
    }

    @Test
    fun 动词变位可查到不定式() = runBlocking {
        val hits = repo.lookup("читал")
        assertTrue(hits.isNotEmpty())
        println("читал -> ${hits.first().lemma} / ${hits.first().matchedTag}")
        assertEquals("читать", hits.first().lemma)
    }

    @Test
    fun 不输入重音符号也能查到() = runBlocking {
        // 带重音与不带重音都应命中
        val withStress = repo.lookup("соба́ка")
        val withoutStress = repo.lookup("собака")
        assertTrue(withStress.isNotEmpty())
        assertTrue(withoutStress.isNotEmpty())
        assertEquals(withStress.first().entryId, withoutStress.first().entryId)
    }

    @Test
    fun 变格表包含完整的格变化() = runBlocking {
        val hits = repo.lookup("собака")
        val entryId = hits.first().entryId
        val forms = repo.forms(entryId)
        println("собака 变形数=${forms.size}")
        assertTrue("变形表应包含多个形式", forms.size >= 6)
        val tags = forms.mapNotNull { it.tags }.joinToString("|")
        listOf("genitive", "dative", "accusative", "instrumental", "prepositional")
            .forEach { case ->
                assertTrue("变形表应包含 $case", tags.contains(case))
            }
    }

    @Test
    fun 词条带IPA与释义字段() = runBlocking {
        val hits = repo.lookup("собака")
        val entry = repo.entry(hits.first().entryId)
        assertNotNull(entry)
        println("IPA=${entry!!.ipa} 释义=${entry.glossZh ?: entry.glossEn}")
        assertTrue("应有 IPA", !entry.ipa.isNullOrBlank())
        assertTrue("应有释义", !entry.glossZh.isNullOrBlank() || !entry.glossEn.isNullOrBlank())
    }

    @Test
    fun 前缀联想可用() = runBlocking {
        val s = repo.suggest("собак")
        println("собак* -> " + s.take(5).joinToString { it.lemma })
        assertTrue("应有联想结果", s.isNotEmpty())
    }

    @Test
    fun 生词本可增删查() = runBlocking {
        val hits = repo.lookup("собака")
        val id = hits.first().entryId
        repo.removeFromWordbook(id)          // 先清干净
        assertTrue("初始不应在生词本", !repo.isInWordbook(id))
        repo.addToWordbook(id)
        assertTrue("加入后应存在", repo.isInWordbook(id))
        repo.setMastery(id, 2)
        repo.removeFromWordbook(id)
        assertTrue("删除后应不存在", !repo.isInWordbook(id))
    }

    @Test
    fun 别名变形条目能定位到真原形() = runBlocking {
        // читаю（第一人称单数）应指向 читать 而不是自身
        val hits = repo.lookup("читаю")
        assertEquals("читать", hits.first().lemma)
    }
}
