package com.liuxue.assistant.feature.files

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
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
import com.liuxue.assistant.data.db.EmergencyInfo
import com.liuxue.assistant.util.ShareUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(
    onBack: () -> Unit,
    vm: EmergencyViewModel = viewModel<EmergencyViewModel>()
) {
    val items by vm.items.collectAsState(initial = emptyList())
    val context = LocalContext.current
    var editing by remember { mutableStateOf<EmergencyInfo?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EmergencyInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("紧急信息") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    IconButton(onClick = {
                        val text = items.joinToString("\n") { it.label + "：" + it.value }
                        if (text.isNotBlank()) ShareUtils.shareText(context, text, "分享紧急信息")
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "分享全部")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showEditor = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加") }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有紧急信息\n建议录入：血型、过敏史、家人电话、使馆电话、保险单号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EmergencyViewModel.GROUPS.forEach { (key, title) ->
                val groupItems = items.filter { it.group == key }
                if (groupItems.isNotEmpty()) {
                    item {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(groupItems, key = { it.id }) { info ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        info.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        info.value,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (info.note.isNotBlank()) {
                                        Text(
                                            info.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    editing = info
                                    showEditor = true
                                }) {
                                    Icon(Icons.Filled.Add, contentDescription = "编辑")
                                }
                                IconButton(onClick = { deleting = info }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        EmergencyEditor(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = {
                vm.save(it)
                showEditor = false
            }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条紧急信息？") },
            text = { Text(target.label + "：" + target.value) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmergencyEditor(
    initial: EmergencyInfo?,
    onDismiss: () -> Unit,
    onSave: (EmergencyInfo) -> Unit
) {
    var group by remember { mutableStateOf(initial?.group ?: EmergencyInfo.GROUP_PERSONAL) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var value by remember { mutableStateOf(initial?.value ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加紧急信息" else "编辑") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EmergencyViewModel.GROUPS.forEach { (key, title) ->
                        if (key == EmergencyInfo.GROUP_PERSONAL || key == EmergencyInfo.GROUP_CONTACT) {
                            FilterChip(
                                selected = group == key,
                                onClick = { group = key },
                                label = { Text(title) }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EmergencyViewModel.GROUPS.forEach { (key, title) ->
                        if (key == EmergencyInfo.GROUP_INSTITUTION || key == EmergencyInfo.GROUP_OTHER) {
                            FilterChip(
                                selected = group == key,
                                onClick = { group = key },
                                label = { Text(title) }
                            )
                        }
                    }
                }

                // 快捷模板
                val templates = EmergencyViewModel.templatesFor(group)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    templates.take(3).forEach { t ->
                        AssistChip(
                            onClick = { if (label.isBlank()) label = t },
                            label = { Text(t, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称 *") },
                    placeholder = { Text("如：血型 / 家人电话") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("内容 *") },
                    placeholder = { Text("如：O型 / +7 999 123 4567") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (label.isBlank() || value.isBlank()) {
                    error = "名称和内容不能为空"
                } else {
                    onSave(
                        (initial ?: EmergencyInfo(
                            group = group, label = label, value = value, note = note
                        )).copy(
                            group = group,
                            label = label.trim(),
                            value = value.trim(),
                            note = note.trim()
                        )
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
