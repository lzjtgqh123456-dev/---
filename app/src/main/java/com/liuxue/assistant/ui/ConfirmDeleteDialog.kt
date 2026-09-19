package com.liuxue.assistant.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 统一的「删除二次确认」弹窗。
 *
 * 设计约定：**所有删除入口都必须先经过这里**，不允许点了图标就直接删。
 * 文案固定给出「不可恢复」提示，删除键用错误色，取消键放右侧（Material 习惯）。
 */
@Composable
fun ConfirmDeleteDialog(
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "确认删除？",
    confirmLabel: String = "删除"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
