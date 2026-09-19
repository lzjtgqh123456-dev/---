package com.liuxue.assistant.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 必填项没填 / 信息不完整时的弹窗提示（比页面底部小字更容易看到） */
@Composable
fun WarnDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("还差一点") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
