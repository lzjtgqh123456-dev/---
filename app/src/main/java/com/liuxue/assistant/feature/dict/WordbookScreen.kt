package com.liuxue.assistant.feature.dict

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.ui.ConfirmDeleteDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordbookScreen(
    onBack: () -> Unit,
    vm: WordbookViewModel = viewModel<WordbookViewModel>()
) {
    val state by vm.state.collectAsState(initial = WordbookState())
    val total by vm.totalCount.collectAsState(initial = 0)
    val msg by vm.message.collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }
    var showAddCategory by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<WordbookRow?>(null) }
    var pendingDeleteCat by remember { mutableStateOf(false) }

    LaunchedEffect(msg) {
        msg?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生词本（$total）") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    IconButton(onClick = { showAddCategory = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建分类")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.categoryId < 0,
                        onClick = { vm.selectCategory(-1L) },
                        label = { Text("全部") }
                    )
                }
                items(state.categories, key = { it.id }) { c ->
                    FilterChip(
                        selected = state.categoryId == c.id,
                        onClick = { vm.selectCategory(c.id) },
                        label = { Text(c.name) }
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.categoryId >= 0) {
                    TextButton(onClick = { pendingDeleteCat = true }) {
                        Text("删除当前分类", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (state.rows.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "生词本还是空的\n在词典里点 ♥ 即可收藏",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.rows, key = { it.item.id }) { row ->
                    WordbookCard(
                        row = row,
                        onMastery = { vm.setMastery(row.item, it) },
                        onRemove = { pendingRemove = row }
                    )
                }
            }
        }
    }

    pendingRemove?.let { row ->
        ConfirmDeleteDialog(
            text = "把「" + (row.entry?.lemma ?: "该词") + "」移出生词本？",
            confirmLabel = "移出",
            onConfirm = { vm.remove(row.item) },
            onDismiss = { pendingRemove = null }
        )
    }

    if (pendingDeleteCat) {
        val cat = state.categories.firstOrNull { it.id == state.categoryId }
        ConfirmDeleteDialog(
            text = "删除分类「" + (cat?.name ?: "当前分类") + "」？生词会保留。",
            onConfirm = { vm.deleteCategory(state.categoryId) },
            onDismiss = { pendingDeleteCat = false }
        )
    }

    if (showAddCategory) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCategory = false },
            title = { Text("新建生词分类") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    placeholder = { Text("如：高频动词 / 考试重点") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addCategory(name)
                    showAddCategory = false
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategory = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun WordbookCard(
    row: WordbookRow,
    onMastery: (Int) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.entry?.lemma ?: "（词条缺失）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        RuGrammar.zh(row.entry?.pos ?: "") +
                            (row.entry?.ipa?.let { "  " + it } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "移出")
                }
            }
            Text(
                row.entry?.glossZh ?: row.entry?.glossEn.orEmpty(),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..3).forEach { level ->
                    FilterChip(
                        selected = row.item.mastery == level,
                        onClick = { onMastery(level) },
                        label = {
                            Text(
                                WordbookViewModel.masteryLabel(level),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}
