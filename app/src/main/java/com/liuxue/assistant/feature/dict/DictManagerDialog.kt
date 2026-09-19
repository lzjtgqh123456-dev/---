package com.liuxue.assistant.feature.dict

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuxue.assistant.data.dict.DictAssetDb
import com.liuxue.assistant.ui.ConfirmDeleteDialog

/**
 * 词典管理：安装 / 删除 / 导出 / 下载。
 *
 * 本 App 不提供服务器：词典可以「从文件导入」（微信/USB/网盘传过来），
 * 也可以把已装好的词典「导出」分享给别人；想联网装就填一个直链（支持断点续传）。
 */
@Composable
internal fun DictManagerDialog(
    vm: DictViewModel,
    state: DictState,
    onDismiss: () -> Unit
) {
    var importTarget by remember { mutableStateOf<DictAssetDb.PackFile?>(null) }
    var exportTarget by remember { mutableStateOf<DictAssetDb.PackFile?>(null) }
    var deleteTarget by remember { mutableStateOf<DictAssetDb.PackFile?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val f = importTarget
        if (uri != null && f != null) vm.importPack(f, uri)
        importTarget = null
    }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val f = exportTarget
            if (uri != null && f != null) vm.exportPack(f, uri)
            exportTarget = null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("词典管理") },
        text = {
            Column(
                Modifier.height(440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "词典可以直接下载；也能从文件导入，或导出传给别的手机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.managerMessage?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                DictAssetDb.FILES.forEach { f ->
                    val installed = f.key in state.installedFiles
                    val size = state.packSizes[f.key] ?: 0L
                    var url by remember(f.key) { mutableStateOf(vm.packUrl(f)) }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(f.label, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                if (installed) "已安装 · " + human(size)
                                else "未安装 · " + if (f.assetName != null) "随 App 内置" else "需导入或下载",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (installed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (f.assetName == null) {
                                OutlinedTextField(
                                    value = url,
                                    onValueChange = { url = it; vm.setPackUrl(f, it) },
                                    label = {
                                        Text(
                                            if (vm.packHasDefaultSource(f)) "下载地址（默认已填）"
                                            else "下载地址"
                                        )
                                    },
                                    placeholder = { Text("https://…/dict_ru.db") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                )
                                if (vm.packHasDefaultSource(f)) {
                                    Text(
                                        "默认走 hf-mirror，失败自动换官网；支持断点续传",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (state.downloadingKey == f.key) {
                                LinearProgressIndicator(
                                    progress = { state.downloadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                )
                                Text(
                                    "下载中 %.0f%%（源 %d）".format(
                                        state.downloadProgress, state.downloadMirror + 1
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (f.assetName == null) {
                                    TextButton(onClick = { vm.downloadPack(f) }) { Text("下载") }
                                }
                                TextButton(onClick = {
                                    importTarget = f
                                    importLauncher.launch(arrayOf("*/*"))
                                }) { Text("导入文件") }
                                if (installed) {
                                    TextButton(onClick = {
                                        exportTarget = f
                                        exportLauncher.launch(f.dbName)
                                    }) { Text("导出") }
                                    TextButton(onClick = { deleteTarget = f }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    deleteTarget?.let { f ->
        ConfirmDeleteDialog(
            text = "删除「" + f.label + "」？可释放 " + human(state.packSizes[f.key] ?: 0L) +
                "，之后能重新下载。",
            onConfirm = { vm.deletePack(f) },
            onDismiss = { deleteTarget = null }
        )
    }
}

private fun human(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
