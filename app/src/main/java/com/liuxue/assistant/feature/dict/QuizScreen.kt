package com.liuxue.assistant.feature.dict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 每日随机抽查。
 *
 * 交互刻意做成"先回忆、再看答案"：只显示俄语词与音标，
 * 点一下才展开释义，然后自评掌握程度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    onBack: () -> Unit,
    vm: QuizViewModel = androidx.lifecycle.viewmodel.compose.viewModel<QuizViewModel>()
) {
    val state by vm.ui.collectAsState(initial = QuizState())
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) "抽查设置" else "生词抽查") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "开始抽查" else "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showSettings) {
                SettingsPanel(vm)
                return@Column
            }

            if (state.loading) {
                Text("正在抽取…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            state.message?.let {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            if (state.finished) {
                SummaryPanel(vm, state)
                return@Column
            }

            val card = state.current ?: return@Column

            // 进度
            Text(
                "第 ${state.index + 1} / ${state.total} 个",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { (state.index.toFloat()) / state.total },
                modifier = Modifier.fillMaxWidth()
            )

            // 词卡
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth().clickable { vm.reveal() }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        card.entry?.lemma ?: "（词条缺失）",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    card.entry?.ipa?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }

                    if (!state.revealed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "想一下意思，点这里看答案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text(
                            card.entry?.glossZh ?: card.entry?.glossEn.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            if (state.revealed) {
                Text("这个词你掌握得怎么样？", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    listOf(0 to "不认识", 1 to "模糊").forEach { (m, label) ->
                        OutlinedButton(
                            onClick = { vm.answer(m) },
                            modifier = Modifier.weight(1f)
                        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    listOf(2 to "熟悉", 3 to "已掌握").forEach { (m, label) ->
                        Button(
                            onClick = { vm.answer(m) },
                            modifier = Modifier.weight(1f)
                        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Text(
                    "选择会影响下次复习间隔：不认识的明天再抽到，已掌握的要 30 天后才出现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(onClick = { vm.reveal() }, modifier = Modifier.fillMaxWidth()) {
                    Text("显示答案")
                }
            }
        }
    }
}

// ==================== 抽查结果 ====================

@Composable
private fun SummaryPanel(vm: QuizViewModel, state: QuizState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("本次抽查完成", style = MaterialTheme.typography.titleMedium)
            Text(
                "认识 " + state.knownCount + " / " + state.total + " 个",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            val rate = if (state.total > 0) state.knownCount * 100 / state.total else 0
            Text(
                when {
                    rate >= 90 -> "掌握得很好，继续保持"
                    rate >= 60 -> "不错，不熟的那几个明天会再抽到"
                    else -> "这几个词还要多过几遍，明天会重点抽"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Button(onClick = { vm.restart() }, modifier = Modifier.fillMaxWidth()) {
        Text("再抽一次")
    }
    Text(
        "抽查结果已保存：标记为「不认识的词明天会再抽到」，「已掌握」的要 30 天后才出现。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ==================== 设置面板 ====================

@Composable
private fun SettingsPanel(vm: QuizViewModel) {
    // 用局部可变状态驱动 UI，改动即写入 SharedPreferences
    var enabled by remember { mutableStateOf(vm.isEnabled) }
    var count by remember { mutableStateOf(vm.count) }
    var hour by remember { mutableStateOf(vm.hour) }
    var onlyUnmastered by remember { mutableStateOf(vm.onlyUnmastered) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Text(
                "改动会立即生效并自动保存（无需点保存）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每天自动抽查", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "到点会发一条通知，点开即可开始抽查",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; vm.enable(it) }
                )
            }

            HorizontalDivider()

            Text("每次抽查数量", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(listOf(5, 10, 15, 20, 30)) { n ->
                    FilterChip(
                        selected = count == n,
                        onClick = { count = n; vm.count = n },
                        label = { Text("$n 个", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Text("提醒时间", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(listOf(7, 12, 18, 20, 21, 22)) { h ->
                    FilterChip(
                        selected = hour == h,
                        onClick = { hour = h; vm.hour = h; vm.minute = 0 },
                        label = { Text("%02d:00".format(h), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("只抽查未掌握的", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "开启后跳过已标记为「已掌握」的词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = onlyUnmastered,
                    onCheckedChange = { onlyUnmastered = it; vm.onlyUnmastered = it }
                )
            }

            HorizontalDivider()
            Text(
                "复习节奏：标记为「不认识/模糊」的词第二天会再抽到；「熟悉」的 7 天后；" +
                    "「已掌握」的 30 天后。这是简易间隔重复，让有限的抽查次数覆盖最该复习的词。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            vm.lastScore?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    OutlinedButton(
        onClick = { vm.start() },
        modifier = Modifier.fillMaxWidth()
    ) { Text("立即开始一次抽查") }
}
