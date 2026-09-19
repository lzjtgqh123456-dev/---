package com.liuxue.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.dict.DictRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 汉→俄查词链路测试（用户报"选汉俄没有结果"，这里逐层验证）。
 */
@RunWith(AndroidJUnit4::class)
class CnSearchTest {

    private lateinit var context: Context
    private lateinit var repo: DictRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = DictRepository(context, AppDatabase.get(context))
    }

    @Test
    fun 数据层_中文反查有结果() = runBlocking {
        listOf("狗", "学生", "书", "老师", "中国", "学习", "美丽").forEach { q ->
            val hits = repo.searchChinese(q)
            println("  「$q」-> ${hits.size} 条: " + hits.take(3).joinToString { it.lemma })
            assertTrue("「$q」应查到结果", hits.isNotEmpty())
        }
    }

    @Test
    fun 数据层_中文结果含义字段非空() = runBlocking {
        val hits = repo.searchChinese("学生")
        assertTrue(hits.isNotEmpty())
        val h = hits.first()
        println("  首条: lemma=${h.lemma} glossZh=${h.glossZh}")
        assertTrue("lemma 不应为空", h.lemma.isNotBlank())
        // 反查命中的条目应有中文释义（否则说明匹配逻辑有问题）
        assertTrue("应有中文释义", !h.glossZh.isNullOrBlank())
    }

    @Test
    fun 数据层_英文反查也应有结果() = runBlocking {
        // 兜底：万一某些词没中文，用户还能用英文试
        val hits = repo.searchChinese("dog")
        println("  「dog」-> ${hits.size} 条")
        assertTrue("英文反查也应有结果", hits.isNotEmpty())
    }
}
