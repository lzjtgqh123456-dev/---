package com.liuxue.assistant.feature.dict

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.data.dict.DictAssetDb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictScreen(
    onOpenWordbook: () -> Unit,
    onOpenPhrases: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenTranslate: () -> Unit,
    onOpenQuiz: () -> Unit,
    vm: DictViewModel = viewModel<DictViewModel>()
) {
    val state by vm.state.collectAsState(initial = DictState())
    val snackbar = remember { SnackbarHostState() }
    var showManager by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.pack.label + "词典") },
                actions = {
                    IconButton(onClick = { showManager = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "词典管理")
                    }
                    IconButton(onClick = onOpenWordbook) {
                        Icon(Icons.Filled.Favorite, contentDescription = "生词本")
                    }
                    IconButton(onClick = onOpenPhrases) {
                        Icon(Icons.Filled.List, contentDescription = "短语集")
                    }
                    IconButton(onClick = onOpenTransfer) {
                        Icon(Icons.Filled.Share, contentDescription = "导入导出")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DictAssetDb.ALL.forEach { p ->
                    FilterChip(
                        selected = state.pack.id == p.id,
                        onClick = { vm.setPack(p) },
                        label = { Text(p.label) }
                    )
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = {
                    Text(if (state.mode == SearchMode.FORWARD) state.pack.forwardHint else state.pack.reverseHint)
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "清除")
                            }
                        }
                        IconButton(onClick = { vm.search() }) {
                            Icon(Icons.Filled.Search, contentDescription = "查询")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.mode == SearchMode.FORWARD,
                    onClick = { vm.setMode(SearchMode.FORWARD) },
                    label = { Text(state.pack.forwardLabel) }
                )
                FilterChip(
                    selected = state.mode == SearchMode.REVERSE,
                    onClick = { vm.setMode(SearchMode.REVERSE) },
                    label = { Text(state.pack.reverseLabel) }
                )
                Spacer(Modifier.weight(1f))
                if (state.dictReady) {
                    Text(
                        "${state.dictStats.first / 1000}k 词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 词典包未安装：直接给「下载 / 导入」入口（跟离线翻译模型一样一键装）
            val currentFile = DictAssetDb.FILES.firstOrNull { file ->
                file.packs.any { it.id == state.pack.id }
            }
            if (!state.dictReady && currentFile != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "「" + state.pack.label + "」词典未安装",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        val exp = vm.expectedBytes(currentFile)
                        Text(
                            if (exp > 0) "约 %.0f MB，装好后可随时删除".format(exp / 1048576.0)
                            else "点下载即可（内置包）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.downloadingKey == currentFile.key) {
                            LinearProgressIndicator(
                                progress = { state.downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            )
                            Text(
                                "下载中 %.0f%%（第 %d 个源）".format(
                                    state.downloadProgress, state.downloadMirror + 1
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else {
                            Row(
                                Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = { vm.downloadPack(currentFile) }) { Text("下载词典") }
                                OutlinedButton(onClick = { showManager = true }) { Text("导入 / 管理") }
                            }
                        }
                    }
                }
                return@Column
            }

            // 功能入口：之前生词本只藏在右上角♥图标里，用户找不到，改为显眼入口
            EntryRow(
                onWordbook = onOpenWordbook,
                onQuiz = onOpenQuiz,
                onTranslate = onOpenTranslate
            )

            if (!state.dictReady) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("正在释放词典…", style = MaterialTheme.typography.bodyMedium)
                }
                return@Column
            }

            // 输入联想
            if (state.suggestions.isNotEmpty() && state.results.isEmpty()) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(state.suggestions, key = { it.entryId }) { s ->
                        SuggestionRow(s, state.pack.glossColumn) { vm.search(s.lemma) }
                    }
                }
                return@Column
            }

            if (state.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }

            if (state.results.isEmpty() && !state.searching && state.query.isBlank()) {
                TipsPanel(state.mode)
            } else if (state.results.isEmpty() && !state.searching) {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("没有找到「${state.query}」",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (state.mode == SearchMode.REVERSE) "换个说法试试" else "检查拼写，或只打词干",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.results, key = { it.entry.id }) { card ->
                        EntryCardView(
                            card = card,
                            glossColumn = state.pack.glossColumn,
                            onToggleWordbook = { vm.toggleWordbook(card.entry.id) }
                        )
                    }
                }
            }
        }
    }

    if (showManager) {
        DictManagerDialog(vm = vm, state = state, onDismiss = { showManager = false })
    }
}

@Composable
private fun TipsPanel(mode: SearchMode) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (mode == SearchMode.REVERSE) {
            Text("汉 → 俄 查词", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "输入中文，边打边出结果",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "例：学生 → ученик，狗 → пёс",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "查不到就是还没中文释义，换个说法试试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text("俄 → 汉 查词", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "变格形式自动还原原形，不用输重音",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "例：собаками → собака",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "　　　читал → читать",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
private fun SuggestionRow(
    hit: com.liuxue.assistant.data.dict.LookupHit,
    glossColumn: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(hit.lemma, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(8.dp))
        Text(
            (if (glossColumn == "glossEn") hit.glossEn else hit.glossZh).orEmpty().take(30),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider()
}

@Composable
private fun EntryCardView(
    card: EntryCard,
    glossColumn: String,
    onToggleWordbook: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val e = card.entry

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {

            // 词头
            Text(
                e.lemma,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!e.ipa.isNullOrBlank()) {
                    Text(
                        e.ipa,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(10.dp))
                }
                Text(
                    RuGrammar.zh(e.pos ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleWordbook) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = if (card.inWordbook) "移出生词本" else "加入生词本",
                        tint = if (card.inWordbook) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            // 本次查询命中的语法信息 —— 这是"变格查词"的关键提示
            if (card.matchedTags.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Text(
                        "你查的是：" + RuGrammar.zhTags(card.matchedTags),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 释义：只显示当前入口对应的语言（俄汉=中、俄英=英、英汉=中），不再中英混排
            val primaryGloss = if (glossColumn == "glossEn") e.glossEn else e.glossZh
            if (!primaryGloss.isNullOrBlank()) {
                Text(
                    primaryGloss,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    if (glossColumn == "glossEn") "（暂无英文释义）" else "（暂无中文释义）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 变格/变位表
            if (card.forms.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (expanded) "收起变格表" else "展开变格表（${card.forms.size} 个形式）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    DeclensionTable(card.forms)
                }
            }
        }
    }
}

/** 三个显眼的功能入口 */
@Composable
private fun EntryRow(
    onWordbook: () -> Unit,
    onQuiz: () -> Unit,
    onTranslate: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EntryCard("生词本", "收藏的词", Modifier.weight(1f), onWordbook)
        EntryCard("每日抽查", "随机复习", Modifier.weight(1f), onQuiz)
        EntryCard("整句翻译", "离线模型", Modifier.weight(1f), onTranslate)
    }
}

@Composable
private fun EntryCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        ),
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
