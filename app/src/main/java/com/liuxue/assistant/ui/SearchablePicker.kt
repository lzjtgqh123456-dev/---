package com.liuxue.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 可搜索的快速选择器。
 *
 * 列表很长（班级 100+、课程 1000+、币种几十个）时，一个个 chip 翻太慢；
 * 统一改成「点一下 → 弹窗里输入关键字过滤 → 点选」。
 */
@Composable
fun <T> SearchablePickerField(
    label: String,
    items: List<T>,
    selected: T?,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
    searchOf: (T) -> String = labelOf,
    allowClear: Boolean = true,
    clearLabel: String = "全部",
    emptyHint: String = "暂无可选项",
    onSelect: (T?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = selected?.let(labelOf) ?: clearLabel
    OutlinedButton(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        if (allowClear && selected != null) {
            IconButton(onClick = { onSelect(null) }) {
                Icon(Icons.Filled.Clear, contentDescription = "清除")
            }
        }
    }
    if (open) {
        SearchablePickerDialog(
            title = label,
            items = items,
            selected = selected,
            labelOf = labelOf,
            searchOf = searchOf,
            allowClear = allowClear,
            clearLabel = clearLabel,
            emptyHint = emptyHint,
            onSelect = { onSelect(it); open = false },
            onDismiss = { open = false }
        )
    }
}

@Composable
fun <T> SearchablePickerDialog(
    title: String,
    items: List<T>,
    selected: T?,
    labelOf: (T) -> String,
    searchOf: (T) -> String = labelOf,
    allowClear: Boolean = true,
    clearLabel: String = "全部",
    emptyHint: String = "暂无可选项",
    onSelect: (T?) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val filtered = remember(items, q) {
        if (q.isEmpty()) items else items.filter { searchOf(it).contains(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入关键字搜索…") },
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (filtered.size == items.size) "共 ${items.size} 项"
                    else "匹配 ${filtered.size} / ${items.size} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (allowClear) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(clearLabel, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        if (selected == null) {
                            Icon(Icons.Filled.Check, contentDescription = "已选",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    Text(emptyHint, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(Modifier.height(360.dp)) {
                        items(filtered) { item ->
                            val isSel = item == selected
                            Row(
                                Modifier.fillMaxWidth().clickable { onSelect(item) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    labelOf(item),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSel) {
                                    Icon(Icons.Filled.Check, contentDescription = "已选",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
