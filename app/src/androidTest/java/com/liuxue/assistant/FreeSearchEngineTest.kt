package com.liuxue.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liuxue.assistant.data.net.NetConfig
import com.liuxue.assistant.data.net.SearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 免 Key 搜索实测：必应网页搜索（抓 www.bing.com 结果页）能不能真的返回标题/摘要/链接。
 * 结果写到 files/free_search_dump.txt，便于人工核对。
 */
@RunWith(AndroidJUnit4::class)
class FreeSearchEngineTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 必应免Key搜索能返回结果() = runBlocking {
        val app: Application = ApplicationProvider.getApplicationContext()
        val cfg = NetConfig(app)
        // 只留免 Key 的三个，保证测的就是它们
        cfg.enabledEngines = setOf("bing_cn", "wikipedia", "duckduckgo")
        val repo = SearchRepository(cfg)

        val query = "莫斯科 天气"
        val resp = repo.search(query)
        val byEngine = resp.results.groupBy { it.source }

        val dump = StringBuilder()
        dump.append("查询=").append(query).append('\n')
        dump.append("返回 ").append(resp.results.size).append(" 条；各引擎：")
            .append(byEngine.entries.joinToString { it.key + "=" + it.value.size }).append('\n')
        dump.append("错误：").append(resp.errors.joinToString(" | ").ifBlank { "无" }).append('\n').append('\n')
        resp.results.take(12).forEach { r ->
            dump.append('[').append(r.source).append("] ").append(r.title).append('\n')
                .append("    ").append(r.snippet.take(120)).append('\n')
                .append("    ").append(r.url).append('\n')
        }
        File(ctx.getExternalFilesDir(null), "free_search_dump.txt").writeText(dump.toString())

        val bing = byEngine["必应"].orEmpty()
        assertTrue("必应没返回结果。dump：\n" + dump, bing.size >= 3)
        assertTrue("必应结果没有链接", bing.any { it.url.startsWith("http") })
        assertTrue("必应结果没有摘要", bing.any { it.snippet.isNotBlank() })
    }
}
