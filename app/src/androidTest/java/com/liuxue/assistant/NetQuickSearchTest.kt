package com.liuxue.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liuxue.assistant.data.net.SearchResult
import com.liuxue.assistant.feature.net.NetTab
import com.liuxue.assistant.feature.net.NetViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「搜索 + AI 助手」合并为「AI 搜索」的真机测试：
 * 默认落在合并页、两个开关可切换、空输入有提示、联网资料会拼进给 AI 的提问。
 */
@RunWith(AndroidJUnit4::class)
class NetQuickSearchTest {

    private lateinit var vm: NetViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        vm = NetViewModel(app)
    }

    private fun waitUntil(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    @Test
    fun 搜索与AI已合并为一个AI搜索页() {
        assertEquals("默认应落在 AI 搜索页", NetTab.QUICK, vm.ui.value.tab)
        assertEquals("Tab 应为 AI搜索/汇率/设置 三个", 3, NetTab.entries.size)
    }

    @Test
    fun 联网搜索与深度思考开关可切换() {
        assertFalse(vm.ui.value.webSearch)
        assertFalse(vm.ui.value.deepThink)
        vm.toggleWebSearch()
        vm.toggleDeepThink()
        assertTrue(vm.ui.value.webSearch)
        assertTrue(vm.ui.value.deepThink)
        vm.toggleWebSearch()
        vm.toggleDeepThink()
        assertFalse(vm.ui.value.webSearch)
        assertFalse(vm.ui.value.deepThink)
    }

    @Test
    fun 空输入给出提示() {
        vm.setQuery("   ")
        vm.quickAsk()
        assertTrue("应提示请输入", waitUntil { !vm.ui.value.message.isNullOrBlank() })
        println("空输入提示: " + vm.ui.value.message)
    }

    @Test
    fun 联网资料会拼进给AI的提问() {
        val prompt = vm.buildQuickPrompt(
            "俄罗斯留学要准备什么",
            listOf(
                SearchResult("A 指南", "签证与落地登记", "https://example.com/a", "DuckDuckGo"),
                SearchResult("B 清单", "体检、保险", "https://example.com/b", "维基百科")
            )
        )
        println(prompt)
        // 新版格式：用【用户问题】/【检索结果】分节，并要求 AI 标注来源编号
        assertTrue(prompt, prompt.contains("【用户问题】"))
        assertTrue(prompt, prompt.contains("俄罗斯留学要准备什么"))
        assertTrue(prompt, prompt.contains("[1] A 指南"))
        assertTrue(prompt, prompt.contains("[2] B 清单"))
        assertTrue(prompt, prompt.contains("【检索结果】"))
        assertTrue(prompt, prompt.contains("标注"))
        assertEquals("没有联网结果时原样返回问题", "q", vm.buildQuickPrompt("q", emptyList()))
    }
}
