package com.liuxue.assistant.feature.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.liuxue.assistant.data.study.ClassType
import com.liuxue.assistant.data.study.CourseSchedule

/**
 * 课表导入确认：
 *  - 先展示识别到的格式、班级列表、未识别片段，避免"闷头导入"；
 *  - 矩阵课表（ВШРФК 样式）可勾选要导入的班级（默认全选）；
 *  - 确认后才写入，已存在的同名同班课程会跳过。
 */
@Composable
internal fun ScheduleImportDialog(
    result: ScheduleImporter.Result,
    vm: StudyViewModel,
    onShareTemplate: () -> Unit,
    onDismiss: () -> Unit
) {
    val ok = result.error == null && result.rows.isNotEmpty()
    val classes = result.classes
    var selected by remember(result) { mutableStateOf(classes.toSet()) }
    val chosenRows = if (classes.isEmpty()) result.rows else result.rows.filter { it.className in selected }
    val byCourse = chosenRows.groupBy { it.courseName.trim() to it.className.trim() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ok) "确认导入课表" else "导入失败") },
        text = {
            Column(
                Modifier.height(420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!ok) {
                    Text(result.error ?: "没有解析到内容",
                        color = MaterialTheme.colorScheme.error)
                    if (result.format.startsWith("教室矩阵")) {
                        Text("已按「教室矩阵」格式识别，但没有解析出课程；请核对源文件。",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("请确认表格列的顺序为：",
                            style = MaterialTheme.typography.bodySmall)
                        Text(ScheduleImporter.HEADERS.joinToString(" | "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = onShareTemplate, modifier = Modifier.fillMaxWidth()) {
                        Text("发我一份模板")
                    }
                    return@Column
                }

                Text(
                    "识别格式：" + result.format +
                        (if (result.major.isNotBlank()) " · " + result.major else ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("解析出 ${result.rows.size} 个上课时段，已选 ${chosenRows.size} 个",
                    style = MaterialTheme.typography.titleSmall)
                if (result.unparsed.isNotEmpty()) {
                    Text("有 ${result.unparsed.size} 个片段没认出来（不会导入）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.unparsed.take(3).forEach {
                        Text("· " + it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (classes.isNotEmpty()) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "选择要导入的班级（共 ${classes.size} 个，已选 ${selected.size}）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { selected = classes.toSet() }) { Text("全选") }
                        TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                    }
                    var classQuery by remember(result) { mutableStateOf("") }
                    OutlinedTextField(
                        value = classQuery,
                        onValueChange = { classQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索班级号，如 10.3-622 或 622А") },
                        singleLine = true
                    )
                    val shown = if (classQuery.isBlank()) classes
                    else classes.filter { it.contains(classQuery.trim(), ignoreCase = true) }
                    if (shown.isEmpty()) {
                        Text("没有匹配的班级",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    shown.forEach { c ->
                        val isSel = c in selected
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { selected = if (isSel) selected - c else selected + c }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                c + "（" + (result.classCounts[c] ?: 0) + " 个时段）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSel) {
                                Icon(Icons.Filled.Check, contentDescription = "已选",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    HorizontalDivider()
                }

                Text("将创建 ${byCourse.size} 门课程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                byCourse.entries.take(10).forEach { (key, group) ->
                    val (name, cls) = key
                    HorizontalDivider()
                    Text(
                        name + if (cls.isNotBlank()) "（$cls）" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    group.take(4).forEach { r ->
                        Text(
                            "  " + CourseSchedule.weekdayLabel(r.weekday) + " " +
                                r.startTime + "-" + r.endTime +
                                (if (r.location.isNotBlank()) " @" + r.location else "") +
                                "  " + ClassType.label(r.classType) +
                                "  第" + r.weekFrom + "-" + r.weekTo + "周",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (byCourse.size > 10) {
                    Text("… 还有 ${byCourse.size - 10} 门课程未显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "已存在的同名同班课程会跳过，不会重复导入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (ok) {
                TextButton(
                    enabled = chosenRows.isNotEmpty(),
                    onClick = {
                        vm.importSchedule(chosenRows)
                        onDismiss()
                    }
                ) { Text("确认导入") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (ok) TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
