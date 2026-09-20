package com.liuxue.assistant.feature.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「升级旧的加密文件」弹窗。
 *
 * 背景：老版本用 Android Keystore 直接加密文件，它每个操作都要过 keystore 守护进程，
 * 实测只有 ~2.5MB/s —— 打开一个 129MB 的视频要等一两分钟。新版本改用「信封加密」
 * （数据密钥被 Keystore 包住，正文用软件 AES 跑 AES-NI），快两个数量级。
 *
 * 旧文件在被打开时会自动升级，但那样"第一次打开"依然要等。所以启动时如果发现还有
 * 大体积的旧文件，就主动问用户要不要现在一次性升级 —— 升完以后每次打开都是秒开。
 */
@Composable
fun VaultUpgradeDialog(
    legacyCount: Int,
    upgrading: Boolean,
    doneCount: Int,
    progressText: String,
    onStart: () -> Unit,
    onLater: () -> Unit,
    onClose: () -> Unit
) {
    when {
        // ---------- 升级中 ----------
        upgrading -> AlertDialog(
            onDismissRequest = { /* 升级中不允许点外面关掉 */ },
            title = { Text("正在升级加密文件…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "升级在后台进行，你可以继续用其它功能，不用等在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        progressText.ifBlank { "准备中…" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = {}, enabled = false) { Text("升级中…") } }
        )

        // ---------- 升级完成 ----------
        doneCount > 0 -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("升级完成 ✅") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("已升级 $doneCount 个文件。")
                    Text(
                        "现在打开这些大文件只要 1 秒左右，之前要等一两分钟。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("好的") } }
        )

        // ---------- 询问 ----------
        else -> AlertDialog(
            onDismissRequest = onLater,
            title = { Text("升级旧的加密文件？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("检测到 $legacyCount 个较大的文件还是旧的加密格式。")
                    Text(
                        "旧格式每次打开都要等一两分钟（加密库的限制）。升级成新格式后，打开只要 1 秒左右。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "升级在后台进行，可以继续使用 App；只是换一种加密存储方式，文件内容和密钥都不变，不会丢数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = onStart) { Text("立即升级") } },
            dismissButton = { TextButton(onClick = onLater) { Text("以后再说") } }
        )
    }
}
