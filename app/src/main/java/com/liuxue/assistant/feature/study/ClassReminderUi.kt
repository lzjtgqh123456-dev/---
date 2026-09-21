package com.liuxue.assistant.feature.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
 * 课表页的「上课提醒」入口条：点一下打开设置。
 * 提醒按班级作用域（选定班级后只提醒该班的课）。
 */
@Composable
internal fun ReminderBar(state: StudyUiState, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.reminderEnabled)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔔", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    if (state.reminderEnabled) "上课提醒已开启" else "开启上课提醒",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (state.reminderEnabled)
                        "提前 " + state.reminderMinutes + " 分钟 · 作用：" + (state.reminderScope ?: "全部班级")
                    else "选定班级后，课前自动提醒（时间可自定义）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (state.reminderEnabled) "修改" else "设置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun ClassReminderDialog(
    state: StudyUiState,
    onSave: (Boolean, Int, String?) -> Unit,
    onCheckNow: () -> Unit,
    onDismiss: () -> Unit
) {
    var enabled by remember { mutableStateOf(state.reminderEnabled) }
    var minutes by remember { mutableStateOf(state.reminderMinutes) }
    var scope by remember { mutableStateOf(state.reminderScope) }
    val currentClass = state.classFilter

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上课提醒") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开启提醒", style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        Text("所选班级的课开始前通知你",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text("提前多久", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(5, 10, 15, 20, 30, 45, 60)) { m ->
                        FilterChip(
                            selected = minutes == m,
                            onClick = { minutes = m },
                            label = { Text(m.toString() + " 分钟",
                                style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text("作用班级", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentClass != null) {
                        FilterChip(
                            selected = scope == currentClass,
                            onClick = { scope = currentClass },
                            label = { Text(currentClass, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    FilterChip(
                        selected = scope == null,
                        onClick = { scope = null },
                        label = { Text("全部班级", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Text(
                    "到「上课时间 − 提前分钟数」时提醒，同一节课只提醒一次；后台漏掉会自动补发，无需精确闹钟权限。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(enabled, minutes, scope)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCheckNow) { Text("立即检查") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
