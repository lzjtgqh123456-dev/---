package com.liuxue.assistant.feature.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.util.ShareUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onBack: () -> Unit,
    onOpenNearby: () -> Unit = {},
    vm: TransferViewModel = viewModel<TransferViewModel>()
) {
    val state by vm.state.collectAsState(initial = TransferState())
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.import(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    // 导出完成后触发分享
    LaunchedEffect(state.shareFile) {
        state.shareFile?.let { f ->
            ShareUtils.shareFile(context, f, BackupFormat.MIME, "分享备份文件")
            vm.clearShareFile()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入与导出") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            )) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("面对面快传", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(
                        "两台手机 Wi-Fi Direct 直连，完全离线、不费流量，任意文件随意互传（不限类型/大小）；" +
                            "收到的文件在系统「下载 / 留学助手快传」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onOpenNearby, modifier = Modifier.fillMaxWidth()) {
                        Text("打开面对面快传")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("导出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "生成一个 .lex 备份文件，可通过微信、邮件等发送给他人",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))

                    CheckRow("生词本", state.includeWordbook) { vm.toggleWordbook() }
                    CheckRow("常用短语集", state.includePhrases) { vm.togglePhrases() }
                    CheckRow("证件与文件（仅信息）", state.includeVault) { vm.toggleVault() }
                    if (state.includeVault) {
                        CheckRow(
                            "同时导出证件图片（体积较大，含敏感信息）",
                            state.includeVaultImages
                        ) { vm.toggleVaultImages() }
                    }

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { vm.export() },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("生成并分享备份")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "选择他人分享的 .lex 文件，内容会**合并**进本地，重复项自动跳过，不会覆盖你已有的数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("选择备份文件导入") }
                }
            }

            Text(
                "提示：备份文件不含词典本体；证件图片默认不导出，如需迁移可勾选。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
