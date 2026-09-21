package com.liuxue.assistant.feature.net

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.data.net.SearchEngine
import com.liuxue.assistant.ui.fileEmoji
import com.liuxue.assistant.ui.SearchablePickerField
import com.liuxue.assistant.util.DateUtils

/**
 * P5：搜索 / AI 助手 / 汇率 / 设置。
 *
 * 默认零配置可用（DuckDuckGo + 维基百科 + 汇率都不需要 Key）；
 * 想用更好的引擎或 AI，到「设置」里填 Key —— 每个引擎都写了申请指引。
 */
/**
 * 多引擎联网搜索总开关（AI 提问区那个「联网搜索」）。
 * 用户确认「AI 的联网搜索是要的」，所以保持 true。
 * 置 false 时会隐藏该开关、搜索引擎设置卡片、引擎计数与失败提示。
 */
private const val ENABLE_WEB_SEARCH = false

/** 页内教程卡片总开关（已统一搬到「感谢与教程」文档，这里默认关掉） */
private const val SHOW_TUTORIAL = false

/** 服务端联网检索的常见参数写法（各家不同，点一下填入再测） */
private val AI_EXTRA_PRESETS = listOf(
    "web_search" to "{\"web_search\": true}",
    "enable_search" to "{\"enable_search\": true}",
    "search" to "{\"search\": true}",
    "tools/web_search" to "{\"tools\": [{\"type\": \"web_search\"}]}",
    "plugins/web" to "{\"plugins\": [{\"id\": \"web\"}]}",
    "清了" to ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetScreen(vm: NetViewModel = viewModel<NetViewModel>()) {
    val state by vm.ui.collectAsState(initial = NetUiState())
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("搜索与 AI") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                NetTab.entries.forEach { t ->
                    Tab(
                        selected = state.tab == t,
                        onClick = { vm.setTab(t) },
                        text = { Text(t.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            when (state.tab) {
                NetTab.QUICK -> QuickSearchTab(state, vm)
                NetTab.RATE -> RateTab(state, vm)
                NetTab.SETTINGS -> SettingsTab(state, vm)
            }
        }
    }
}

// ==================== 搜索 ====================

// ==================== 汇率 ====================

@Composable
private fun RateTab(state: NetUiState, vm: NetViewModel) {
    val currencies = vm.availableCurrencies()
    Column(
        // bottom 留白：避开底部导航栏
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 进入汇率页自动拉一次（1 分钟缓存内不重复请求），不用先点按钮
        LaunchedEffect(Unit) { vm.ensureRatesLoaded() }

        OutlinedTextField(
            value = state.rateAmount,
            onValueChange = { vm.setRateAmount(it); vm.computeRate() },
            label = { Text("金额") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchablePickerField(
                label = "从",
                items = currencies.map { it.first },
                selected = state.rateFrom,
                labelOf = { code ->
                    code + " " + (currencies.firstOrNull { it.first == code }?.second ?: "")
                },
                allowClear = false,
                modifier = Modifier.weight(1f),
                onSelect = { it?.let { c -> vm.setRateFrom(c); vm.computeRate() } }
            )
            SearchablePickerField(
                label = "到",
                items = currencies.map { it.first },
                selected = state.rateTo,
                labelOf = { code ->
                    code + " " + (currencies.firstOrNull { it.first == code }?.second ?: "")
                },
                allowClear = false,
                modifier = Modifier.weight(1f),
                onSelect = { it?.let { c -> vm.setRateTo(c); vm.computeRate() } }
            )
        }

        Button(
            onClick = { vm.refreshRates() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.rateLoading
        ) {
            Text(if (state.rateLoading) "刷新中…" else "刷新汇率并换算")
        }

        if (state.rateResult.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        state.rateAmount + " " + state.rateFrom + " =",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(state.rateResult, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    state.rateTable?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "更新于 " + DateUtils.formatDateTime(it.updatedAt) + " · " + it.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { vm.refreshRates() }, enabled = !state.rateLoading) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新汇率")
                            }
                        }
                    }
                }
            }
        }

    }
}

// ==================== 设置（含使用说明）====================

@Composable
private fun SettingsTab(state: NetUiState, vm: NetViewModel) {
    val config = vm.config()
    // 用局部状态驱动，点保存后写回
    var engines by remember { mutableStateOf(config.enabledEngines) }
    var primary by remember { mutableStateOf(config.primaryEngine) }
    var keys by remember { mutableStateOf(SearchEngine.entries.associateWith { config.apiKey(it) }) }
    var googleCx by remember { mutableStateOf(config.googleCx) }
    var searxngUrl by remember { mutableStateOf(config.searxngUrl) }
    var aiEnabled by remember { mutableStateOf(config.aiEnabled) }
    var aiBase by remember { mutableStateOf(config.aiBaseUrl) }
    var aiKey by remember { mutableStateOf(config.aiApiKey) }
    var aiModel by remember { mutableStateOf(config.aiModel) }
    var aiAuth by remember { mutableStateOf(config.aiSendAuth) }
    var aiExtra by remember { mutableStateOf(config.aiExtraJson) }
    var presetName by remember { mutableStateOf("") }
    var presetPrompt by remember { mutableStateOf("") }
    // 保存结果（成功/失败都要看得见）
    var saveResult by remember { mutableStateOf<String?>(null) }

    Column(
        // bottom 留白：避开底部导航栏
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------- 搜索引擎（ENABLE_WEB_SEARCH=false 时整块隐藏）----------
        if (ENABLE_WEB_SEARCH) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("搜索引擎", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(
                    "上面几个免费引擎（必应网页搜索 / 维基百科 / DuckDuckGo）不用任何配置，装上就能用。" +
                        "需要 Key 的引擎收在下面的「高级」里，一般用不到。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 免费引擎直接列出；需要 API Key 的折进"高级"里，页面更清爽
                var showAdvanced by remember { mutableStateOf(false) }
                val freeEngines = SearchEngine.entries.filter { !it.needsKey }
                val keyEngines = SearchEngine.entries.filter { it.needsKey }
                val engineList = if (showAdvanced) freeEngines + keyEngines else freeEngines
                engineList.forEach { e ->
                    val usable = !e.needsKey || keys[e].orEmpty().isNotBlank() ||
                        (e == SearchEngine.GOOGLE_CSE && googleCx.isNotBlank()) ||
                        (e == SearchEngine.SEARXNG && searxngUrl.isNotBlank())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = engines.contains(e.key),
                            onCheckedChange = { on ->
                                engines = if (on) engines + e.key else engines - e.key
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.label + if (e.needsKey) "（需 Key）" else "（免费）",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(e.note, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (e.needsKey) {
                        OutlinedTextField(
                            value = keys[e].orEmpty(),
                            onValueChange = { keys = keys + (e to it) },
                            label = { Text(e.label + " API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (e == SearchEngine.GOOGLE_CSE) {
                            OutlinedTextField(
                                value = googleCx,
                                onValueChange = { googleCx = it },
                                label = { Text("Google CX（搜索引擎 ID）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (e == SearchEngine.SEARXNG) {
                            OutlinedTextField(
                                value = searxngUrl,
                                onValueChange = { searxngUrl = it },
                                label = { Text("SearXNG 实例地址") },
                                placeholder = { Text("https://your.searx.instance") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    HorizontalDivider()
                }
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(
                        if (showAdvanced) "收起需要 API Key 的引擎" +
                            "（" + keyEngines.size + " 个）"
                        else "高级：需要自己申请 API Key 的引擎（" + keyEngines.size + " 个）",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (showAdvanced) {
                    Text(
                        "这些引擎都要自己申请 Key（Google/Bing/Brave/Serper/SearXNG），" +
                            "国内网络通常也连不上；不填不会影响上面三个免费的。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- AI ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 辅助学习", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = aiEnabled,
                        onCheckedChange = { aiEnabled = it }
                    )
                }
                Text(
                    "走 OpenAI 兼容协议（POST {BaseURL}/chat/completions）。" +
                        "常见中转站、DeepSeek、Moonshot、智谱、本地 Ollama 都兼容，" +
                        "只需填下面三项。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = aiBase,
                    onValueChange = { aiBase = it },
                    label = { Text("BaseURL") },
                    placeholder = { Text("https://你的中转站/v1") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = aiKey,
                    onValueChange = { aiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-…（本地服务可留空）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = aiModel,
                    onValueChange = { aiModel = it },
                    label = { Text("模型名") },
                    placeholder = { Text("gpt-4o-mini / deepseek-chat / qwen-plus") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = aiAuth,
                        onCheckedChange = { aiAuth = it }
                    )
                    Text("发送 Authorization 头（本地 Ollama 等可取消）",
                        style = MaterialTheme.typography.labelSmall)
                }

                // 服务商自带的联网检索等，靠"附加请求参数"原样透传（各家格式不一样，不写死）
                OutlinedTextField(
                    value = aiExtra,
                    onValueChange = { aiExtra = it },
                    label = { Text("附加请求参数（JSON，可选）") },
                    placeholder = { Text("{\"web_search\": true}  /  {\"enable_search\": true}") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "服务商自带的联网检索各家参数不同，填这里会原样合并进请求体（填错会在下面的测试结果里报出来）。" +
                        "留空则只用 App 自己的「联网搜索」（DuckDuckGo + 维基百科）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("常用写法（点一下填入，再用「测试 API 响应」验证）：",
                    style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(AI_EXTRA_PRESETS, key = { it.first }) { (label, json) ->
                        AssistChip(
                            onClick = { aiExtra = json },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // 先把当前填的三项写进配置再测（否则测的是上一次保存的配置）
                OutlinedButton(
                    onClick = {
                        config.aiEnabled = aiEnabled
                        config.aiBaseUrl = aiBase
                        config.aiApiKey = aiKey
                        config.aiModel = aiModel
                        config.aiSendAuth = aiAuth
                        config.aiExtraJson = aiExtra
                        vm.testAi()
                    },
                    enabled = !state.aiTesting && aiBase.isNotBlank() && aiModel.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.aiTesting) "正在测试…" else "测试 API 响应") }
                if (state.aiTestResult.isNotBlank()) {
                    val ok = !state.aiTestResult.startsWith("连接失败")
                    Text(
                        state.aiTestResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "测试会真的发一条最小请求（请只回复两个字），失败原因会直接显示在这里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
                Text("自定义预设要求", style = MaterialTheme.typography.labelMedium)
                Text(
                    "保存后会在「提问」页顶部出现一个可点选的标签；提问时把它作为系统提示词发出去。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val customs = state.presets.filter { !it.builtin }
                if (customs.isEmpty()) {
                    Text("（还没有自定义预设）", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    customs.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    p.systemPrompt.let { if (it.length > 60) it.take(60) + "…" else it },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.removePreset(p.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除预设")
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = presetName, onValueChange = { presetName = it },
                    label = { Text("预设名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = presetPrompt, onValueChange = { presetPrompt = it },
                    label = { Text("预设要求（系统提示词）") },
                    placeholder = { Text("例如：你是俄语语法老师，请逐词标注格与数…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (vm.addPreset(presetName, presetPrompt)) {
                                presetName = ""; presetPrompt = ""
                            }
                        },
                        enabled = presetName.isNotBlank() && presetPrompt.isNotBlank()
                    ) { Text("保存预设") }
                    TextButton(
                        onClick = { presetName = ""; presetPrompt = "" },
                        enabled = presetName.isNotBlank() || presetPrompt.isNotBlank()
                    ) { Text("清空") }
                }
            }
        }

        Button(
            onClick = {
                saveResult = runCatching {
                    config.enabledEngines = engines
                    config.primaryEngine = primary
                    keys.forEach { (e, k) -> config.setApiKey(e, k) }
                    config.googleCx = googleCx
                    config.searxngUrl = searxngUrl
                    config.aiEnabled = aiEnabled
                    config.aiBaseUrl = aiBase.trim()
                    config.aiApiKey = aiKey.trim()
                    config.aiModel = aiModel.trim()
                    config.aiSendAuth = aiAuth
                    config.aiExtraJson = aiExtra.trim()
                    vm.refreshAfterSettingsChange()
                }.fold(
                    onSuccess = {
                        val warn = buildString {
                            if (aiEnabled && aiBase.isBlank()) append("（⚠️ AI 开了但没填 BaseURL）")
                            if (aiEnabled && aiModel.isBlank()) append("（⚠️ AI 开了但没填模型名）")
                            if (engines.isEmpty()) append("（⚠️ 没启用任何搜索引擎）")
                        }
                        "✅ 已保存：" + engines.size + " 个搜索引擎 · AI " +
                            (if (aiEnabled) "已开启" else "未开启") +
                            (if (aiExtra.isBlank()) "" else " · 附加参数已保存") + warn
                    },
                    onFailure = { "❌ 保存失败：" + (it.message ?: "未知错误") }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存全部设置") }
        saveResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("✅")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        if (SHOW_TUTORIAL) {
        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("关于这一页（哪些联网、哪些不联网）",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                InfoRow("完全离线", "证件管理、俄汉词典、生词本、学业管理、每日抽查")
                InfoRow("需要联网", "本页的汇率 / AI；以及离线翻译模型与 OCR 模型的首次下载")
                InfoRow("离线翻译", "模型下载后不需要网络，原文不上传任何服务器")
                InfoRow("汇率", "只把你输入的金额/币种发给汇率接口（open.er-api.com），不发送其他数据")
                InfoRow("必应网页搜索", "把关键词发到 www.bing.com 并解析搜索结果页的标题/摘要/链接（免 Key）")
                InfoRow("AI", "对话内容会发送到你自己配置的服务端；服务端如何处理取决于该服务商")
                InfoRow("Key 存放", "所有 API Key 只存在本机应用私有目录，不参与云备份，也不会上传")
                HorizontalDivider()
                Text("免 Key 引擎", style = MaterialTheme.typography.labelMedium)
                Text("免 Key 引擎", style = MaterialTheme.typography.labelMedium)
                Text("· DuckDuckGo 即时答案 -> api.duckduckgo.com",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· 维基百科 -> zh.wikipedia.org（中文条目）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· 汇率 -> open.er-api.com，回退 frankfurter.app",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("需 Key 引擎申请入口", style = MaterialTheme.typography.labelMedium)
                Text("· Google 可编程搜索 -> programmablesearchengine.google.com 拿 CX，console.cloud.google.com 拿 Key",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· Bing -> portal.azure.com 创建 Bing Search 资源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· Serper -> serper.dev 注册即给免费额度，国内可直连",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· Brave -> api.search.brave.com 申请",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· SearXNG -> 自建实例（Docker 一条命令），或找公开实例",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("需 Key 引擎申请入口", style = MaterialTheme.typography.labelMedium)
                Text("· Google 可编程搜索 -> programmablesearchengine.google.com 拿 CX，console.cloud.google.com 拿 Key",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· Bing -> portal.azure.com 创建 Bing Search 资源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· Serper -> serper.dev 注册即给免费额度，国内可直连",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· Brave -> api.search.brave.com 申请",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· SearXNG -> 自建实例（Docker 一条命令），或找公开实例",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ==================== 快捷 AI 搜索（搜索 + AI 合并，P6） ====================

@Composable
private fun QuickSearchTab(state: NetUiState, vm: NetViewModel) {
    val context = LocalContext.current
    val config = vm.config()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { vm.setQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            minLines = 2
        )
        // 附件（图片 / 文本 / Word / Excel），选中后随问题一起发出去
        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris -> vm.addAttachments(uris) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Text("📎 添加附件", style = MaterialTheme.typography.labelSmall)
            }
            if (state.attachments.isNotEmpty()) {
                TextButton(onClick = { vm.clearAttachments() }) {
                    Text("清空附件（" + state.attachments.size + "）",
                        style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text(
                    "图片 / PDF / Word / Excel / PPT / 文本（单个 ≤200MB）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.attachments.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.attachments, key = { it.uri.toString() }) { a ->
                    InputChip(
                        selected = false,
                        onClick = { vm.removeAttachment(a.uri) },
                        label = {
                            Text(
                                fileEmoji(a.mime, a.name) + " " +
                                    (if (a.name.length > 16) a.name.take(16) + "…" else a.name),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "移除附件",
                                modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ENABLE_WEB_SEARCH) {
                FilterChip(
                    selected = state.webSearch,
                    onClick = { vm.toggleWebSearch() },
                    label = { Text("联网搜索", style = MaterialTheme.typography.labelSmall) }
                )
            }
            FilterChip(
                selected = state.deepThink,
                onClick = { vm.toggleDeepThink() },
                label = { Text("深度思考", style = MaterialTheme.typography.labelSmall) }
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.toggleHistory() }) {
                Text("历史（${state.qaHistory.size}）", style = MaterialTheme.typography.labelSmall)
            }
            if (ENABLE_WEB_SEARCH) {
                Text(
                    vm.usableEngines().size.toString() + " 个引擎",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.webSearch) {
            // 两步走：先搜，看过结果再让 AI 结合这些资料回答
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.searchNow() },
                    enabled = !state.searching,
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.searching) "搜索中…" else "🔍 搜索") }
                Button(
                    onClick = { vm.askWithResults() },
                    enabled = !state.aiBusy && (state.results.isNotEmpty() || state.query.isNotBlank()),
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.aiBusy) "思考中…" else "🤖 AI 回答") }
            }
            if (state.results.isNotEmpty()) {
                Text(
                    "已搜到 " + state.results.size + " 条；点「AI 回答」= 让 AI 结合这些资料作答并标注来源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
            }
        } else {
            Button(
                onClick = { vm.askWithResults() },
                enabled = !state.aiBusy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(if (state.aiBusy) "思考中…" else "🤖 问 AI") }
        }

        if (!config.isAiReady()) {
            val configured = config.isAiConfigured()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (configured) "AI 总开关没打开" else "AI 还没配置",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        if (configured)
                            "BaseURL/模型名已经填好了，只差「设置」页里 AI 卡片右上角的开关没打开——打开就能问各种问题。"
                        else
                            "到「设置」页填入 BaseURL、API Key、模型名即可。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    TextButton(onClick = { vm.setTab(NetTab.SETTINGS) }) { Text("去设置") }
                }
            }
        }

        LazyColumn(
            // bottom 留白：避开底部导航栏
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.presets.isNotEmpty()) {
                item {
                    Text("预设要求（可不选；再点一下已选的也能取消）",
                        style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = state.selectedPresetId == NO_PRESET,
                                onClick = { vm.clearPreset() },
                                label = { Text("不用预设", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        items(state.presets, key = { it.id }) { p ->
                            FilterChip(
                                selected = state.selectedPresetId == p.id,
                                onClick = { vm.selectPreset(p.id) },
                                label = { Text(p.name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
            if (state.aiBusy) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
            if (state.aiAnswer.isNotBlank()) {
                item {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("AI 回答", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(state.aiAnswer, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            if (state.searching) {
                item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
            if (state.results.isNotEmpty()) {
                item {
                    Text(
                        "联网结果（" + state.results.size + " 条）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(state.results) { r ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (r.url.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(r.url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            if (r.snippet.isNotBlank()) {
                                Text(r.snippet, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            Text(r.source, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            if (ENABLE_WEB_SEARCH && state.searchErrors.isNotEmpty()) {
                item {
                    if (state.results.isEmpty()) {
                        // 全都失败：这才是真需要用户处理的
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                        )) {
                            Column(Modifier.padding(12.dp)) {
                                Text("所有引擎都没返回结果",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error)
                                state.searchErrors.forEach {
                                    Text(it, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    "国内网络下 Google / Bing / Brave 等通常连不上；" +
                                        "可以到设置里只留「免费」的 DuckDuckGo 与维基百科。",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    } else {
                        // 还有结果：只说一句，别弹红卡吓人；想看原因再展开
                        var showErr by remember { mutableStateOf(false) }
                        Column {
                            TextButton(onClick = { showErr = !showErr }) {
                                Text(
                                    "ℹ️ 另有 " + state.searchErrors.size + " 个引擎没返回结果" +
                                        if (showErr) "（收起）" else "（查看原因）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (showErr) {
                                state.searchErrors.forEach {
                                    Text("· " + it, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (state.aiHistory.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已累积 " + (state.aiHistory.size / 2) + " 轮上下文",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.clearAiHistory() }) { Text("清空上下文") }
                    }
                }
            }
        }
    }

    if (state.showHistory) {
        AlertDialog(
            onDismissRequest = { vm.toggleHistory() },
            title = { Text("问答历史（${state.qaHistory.size}）") },
            text = {
                if (state.qaHistory.isEmpty()) {
                    Text("还没有历史记录")
                } else {
                    LazyColumn(Modifier.height(380.dp)) {
                        items(state.qaHistory, key = { it.id }) { item ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { vm.openHistory(item) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    item.question,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    item.answer,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    DateUtils.formatDateTime(item.at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.toggleHistory() }) { Text("关闭") } },
            dismissButton = {
                if (state.qaHistory.isNotEmpty()) {
                    TextButton(onClick = { vm.clearQaHistory() }) { Text("清空") }
                }
            }
        )
    }
}
