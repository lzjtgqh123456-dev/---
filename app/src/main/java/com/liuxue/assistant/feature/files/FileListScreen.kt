package com.liuxue.assistant.feature.files

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.liuxue.assistant.data.db.VaultFile
import com.liuxue.assistant.domain.VaultCategory
import com.liuxue.assistant.feature.files.components.ExpiryBadge
import com.liuxue.assistant.ui.ConfirmDeleteDialog
import com.liuxue.assistant.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    onOpenEmergency: () -> Unit,
    onOpenTransfer: () -> Unit,
    vm: FileListViewModel = viewModel<FileListViewModel>()
) {
    val items by vm.items.collectAsState(initial = emptyList())
    val query by vm.query.collectAsState(initial = "")
    val selectedCategory by vm.category.collectAsState(initial = null)
    val vaultSize by vm.vaultSize.collectAsState(initial = 0L)
    var pendingDelete by remember { mutableStateOf<VaultFile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("证件与文件") },
                actions = {
                    IconButton(onClick = onOpenTransfer) {
                        Icon(Icons.Filled.Share, contentDescription = "导入导出")
                    }
                    IconButton(onClick = onOpenEmergency) {
                        Icon(Icons.Filled.Info, contentDescription = "紧急信息")
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜索名称或备注") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { vm.selectCategory(null) },
                        label = { Text("全部") }
                    )
                }
                items(VaultCategory.entries.toList()) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat.key,
                        onClick = { vm.selectCategory(cat.key) },
                        label = { Text(cat.emoji + " " + cat.label) }
                    )
                }
            }

            if (vaultSize > 0) {
                Text(
                    text = "加密占用 " + formatSize(vaultSize) + " · 共 " + items.size + " 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 大号添加按钮：放在内容区里而不是 Scaffold 的 FAB 槽，
            // 避免在超长屏幕上与底部导航栏重叠导致点不到
            Button(
                onClick = onCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("添加证件 / 文件", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))

            if (items.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        FileCard(
                            item = item,
                            onClick = { onOpen(item.id) },
                            onTogglePin = { vm.togglePin(item) },
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        ConfirmDeleteDialog(
            text = "删除「" + item.title.ifBlank { "未命名" } + "」？附件会一起删。",
            onConfirm = { vm.delete(item) },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("还没有证件", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "点下面「添加」录入护照、签证等\n可拍照或从相册选，自动加密",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FileCard(
    item: VaultFile,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val cat = VaultCategory.fromKey(item.category)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(cat.emoji, style = MaterialTheme.typography.headlineSmall)
            }

            Spacer(Modifier.size(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title.ifBlank { "未命名" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (item.pinned) {
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "已置顶",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = cat.emoji + " " + cat.label +
                        if (item.issueDate != null) " · 签发 " + DateUtils.formatDay(item.issueDate) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                ExpiryBadge(item.expireDate, item.remindDaysBefore)
            }

            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "置顶",
                    tint = if (item.pinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
