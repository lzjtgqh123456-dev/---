package com.liuxue.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.dict.DictAssetDb
import com.liuxue.assistant.data.dict.DictRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 多词典包真机测试：英汉包（ECDICT 常用词）查词、复数/过去式等变形还原、汉→英反查，
 * 以及包切换后俄汉包仍正常。
 */
@RunWith(AndroidJUnit4::class)
class DictEnZhTest {

    private lateinit var context: Context
    private lateinit var repo: DictRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = DictRepository(context, AppDatabase.get(context))
    }

    @Test
    fun 英汉包能释放且词条数量合理() = runBlocking {
        repo.setPack(DictAssetDb.EN_ZH)
        val (entries, translated) = repo.stats()
        println("英汉词条数=$entries 有中文释义=$translated")
        assertTrue("英汉词条数应不少于 50000，实际=$entries", entries >= 50_000)
        assertTrue("应有中文释义", translated >= 50_000)
    }

    @Test
    fun 英文正查拿到中文释义() = runBlocking {
        repo.setPack(DictAssetDb.EN_ZH)
        val hits = repo.lookup("dog")
        assertTrue("dog 应查到", hits.isNotEmpty())
        val first = hits.first()
        println("dog -> ${first.lemma} / ${first.glossZh}")
        assertEquals("dog", first.lemma)
        assertTrue("应有中文释义", first.glossZh?.contains("狗") == true)
    }

    @Test
    fun 英文变形可还原原形() = runBlocking {
        repo.setPack(DictAssetDb.EN_ZH)
        val hits = repo.lookup("dogs")
        assertTrue("dogs 应有结果", hits.isNotEmpty())
        println("dogs -> " + hits.take(3).joinToString { it.lemma + "(" + it.isLemma + ")" })
        assertTrue("dogs 的命中里应包含原形 dog", hits.any { it.lemma.equals("dog", true) })
    }

    @Test
    fun 中文反查英文() = runBlocking {
        repo.setPack(DictAssetDb.EN_ZH)
        val hits = repo.searchChinese("狗")
        assertTrue("「狗」应有反查结果", hits.isNotEmpty())
        println("狗 -> " + hits.take(3).joinToString { it.lemma })
        assertTrue("反查结果里应有 dog", hits.any { it.lemma.equals("dog", true) })
    }

    @Test
    fun 切回俄汉包仍可查变形() = runBlocking {
        repo.setPack(DictAssetDb.EN_ZH)
        repo.lookup("dog")
        repo.setPack(DictAssetDb.RU_ZH)
        val hits = repo.lookup("собаками")
        assertTrue("切回后 собаками 应有结果", hits.isNotEmpty())
        assertEquals("собака", hits.first().lemma)
    }
}
