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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.data.dict.Phrase
import com.liuxue.assistant.ui.ConfirmDeleteDialog
import com.liuxue.assistant.util.ShareUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhraseScreen(
    onBack: () -> Unit,
    vm: PhraseViewModel = viewModel<PhraseViewModel>()
) {
    val state by vm.state.collectAsState(initial = PhraseState())
    val query by vm.query.collectAsState(initial = "")
    val msg by vm.message.collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Phrase?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Phrase?>(null) }

    LaunchedEffect(msg) {
        msg?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("常用短语（" + state.phrases.size + "）") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    IconButton(onClick = {
                        if (state.phrases.isNotEmpty()) {
                            ShareUtils.shareText(context, vm.exportText(), "分享短语集")
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "导出分享")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showEditor = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加短语") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜索短语 / 中文 / 备注") },
                singleLine = true
            )

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

            if (state.phrases.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有短语\n点右下角添加，例如：\nЗдравствуйте! | 您好！",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.phrases, key = { it.id }) { p ->
                    PhraseCard(
                        phrase = p,
                        onStar = { vm.toggleStar(p) },
                        onEdit = { editing = p; showEditor = true },
                        onDelete = { pendingDelete = p }
                    )
                }
            }
        }
    }

    if (showEditor) {
        PhraseEditor(
            initial = editing,
            categories = state.categories,
            defaultCategory = state.categoryId,
            onDismiss = { showEditor = false },
            onSave = { textRu, textZh, note, catId ->
                vm.add(textRu, textZh, note, catId)
                showEditor = false
            },
            onNewCategory = { vm.addCategory(it) }
        )
    }

    pendingDelete?.let { p ->
        ConfirmDeleteDialog(
            text = "删除短语「" + p.textRu + "」？",
            onConfirm = { vm.delete(p) },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun PhraseCard(
    phrase: Phrase,
    onStar: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    phrase.textRu,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onStar) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "收藏",
                        tint = if (phrase.starred) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            Text(phrase.textZh, style = MaterialTheme.typography.bodyLarge)
            if (phrase.note.isNotBlank()) {
                Text(
                    phrase.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) {
                Text("编辑", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PhraseEditor(
    initial: Phrase?,
    categories: List<com.liuxue.assistant.data.dict.PhraseCategory>,
    defaultCategory: Long,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Long) -> Unit,
    onNewCategory: (String) -> Unit
) {
    var ru by remember { mutableStateOf(initial?.textRu ?: "") }
    var zh by remember { mutableStateOf(initial?.textZh ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var catId by remember {
        mutableStateOf(if (initial != null) initial.categoryId else defaultCategory.coerceAtLeast(0L))
    }
    var newCat by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加短语" else "编辑短语") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ru,
                    onValueChange = { ru = it },
                    label = { Text("俄语") },
                    placeholder = { Text("Здравствуйте!") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = zh,
                    onValueChange = { zh = it },
                    label = { Text("中文") },
                    placeholder = { Text("您好！") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("分类", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = catId == 0L,
                            onClick = { catId = 0L },
                            label = { Text("未分类") }
                        )
                    }
                    items(categories, key = { it.id }) { c ->
                        FilterChip(
                            selected = catId == c.id,
                            onClick = { catId = c.id },
                            label = { Text(c.name) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCat,
                        onValueChange = { newCat = it },
                        label = { Text("新建分类") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        if (newCat.isNotBlank()) { onNewCategory(newCat.trim()); newCat = "" }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "创建分类")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ru, zh, note, catId) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
