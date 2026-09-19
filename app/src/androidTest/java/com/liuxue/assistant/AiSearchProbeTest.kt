package com.liuxue.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liuxue.assistant.data.net.AiRepository
import com.liuxue.assistant.feature.net.NetViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 探测：用户配置的 AI 服务端（deepseek-flash）支持哪种"服务端联网检索"参数。
 *
 * 做法：把常见写法逐个塞进「附加请求参数」，各发一条最小请求，记录成功/失败原文；
 * 再对成功的写法问一个时效问题，看模型是否真的能给出联网信息。
 * 结果写到 files/ai_search_probe.txt。
 */
@RunWith(AndroidJUnit4::class)
class AiSearchProbeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun dump(sb: StringBuilder) =
        File(ctx.getExternalFilesDir(null), "ai_search_probe.txt").writeText(sb.toString())

    @Test
    fun 探测服务端联网参数() = runBlocking {
        val app: Application = ApplicationProvider.getApplicationContext()
        val vm = NetViewModel(app)
        val cfg = vm.config()
        assumeTrue("设备上没配置 AI，跳过", cfg.isAiConfigured() && cfg.aiEnabled)
        val repo = AiRepository(app, cfg)
        val out = StringBuilder()
        out.append("模型=").append(cfg.aiModel)
            .append("  BaseURL=").append(cfg.aiBaseUrl).append('\n').append('\n')

        val candidates = listOf(
            "（留空，基线）" to "",
            "{\"web_search\":true}" to "{\"web_search\":true}",
            "{\"enable_search\":true}" to "{\"enable_search\":true}",
            "{\"search\":true}" to "{\"search\":true}",
            "{\"tools\":[{\"type\":\"web_search\"}]}" to "{\"tools\":[{\"type\":\"web_search\"}]}",
            "{\"plugins\":[{\"id\":\"web\"}]}" to "{\"plugins\":[{\"id\":\"web\"}]}",
            "{\"tools\":[{\"type\":\"web_search\",\"web_search\":{\"enable\":true}}]}" to
                "{\"tools\":[{\"type\":\"web_search\",\"web_search\":{\"enable\":true}}]}"
        )
        val okOnes = ArrayList<String>()
        for ((label, json) in candidates) {
            cfg.aiExtraJson = json
            dump(out)   // 先把上一轮结果落盘，长测试也能看到中间结果
            val r = runCatching { repo.test() }.getOrElse { "异常：" + (it.message ?: "") }
            val ok = r.startsWith("✅")
            if (ok) okOnes.add(label + "  =>  " + json)
            out.append(if (ok) "[OK]   " else "[FAIL] ").append(label).append(" -> ")
                .append(r.replace('\n', ' ')).append('\n')
        }
        cfg.aiExtraJson = ""

        // 对"被接受"的写法再问一个时效问题，看是不是真的联网了
        for (json in candidates.drop(1).map { it.second }) {
            if (okOnes.none { it.endsWith(json) }) continue
            cfg.aiExtraJson = json
            val ans = runCatching {
                repo.chat(
                    "现在是 2026 年 9 月 19 日。请联网搜索并用一句话回答：今天有什么国际新闻？" +
                        "如果你没有联网能力，请只回复四个字：无联网。",
                    ignoreEnabled = true
                )
            }.getOrElse { "请求失败：" + (it.message ?: "") }
            out.append('\n').append("=== 时效探测（附加参数 ").append(json).append("）\n")
                .append(ans.take(600)).append('\n')
        }
        cfg.aiExtraJson = ""

        File(ctx.getExternalFilesDir(null), "ai_search_probe.txt").writeText(out.toString())
        assertTrue("探测结果为空", out.isNotBlank())
    }
}
