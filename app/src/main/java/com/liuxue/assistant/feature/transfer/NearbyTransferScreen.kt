package com.liuxue.assistant.feature.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.BuildConfig
import com.liuxue.assistant.data.transfer.WifiDirectController

/**
 * 面对面快传：Wi-Fi Direct 直连，完全离线；连接后任意文件双向互传，
 * 收到的文件统一放在系统「下载 / 留学助手快传」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyTransferScreen(
    onBack: () -> Unit,
    vm: NearbyTransferViewModel = viewModel<NearbyTransferViewModel>()
) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val snackbar = remember { SnackbarHostState() }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 传大文件时别让屏幕灭掉
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addFiles(uris) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) pendingAction?.invoke()
        else pendingAction = null
    }

    fun requirePermission(action: () -> Unit) {
        if (WifiDirectController.hasPermission(context)) action()
        else {
            pendingAction = action
            val perms = buildList {
                addAll(WifiDirectController.permissionsToRequest())
                // Android 9 及以下把收到的文件写进公共「下载」目录还需要存储权限
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                    add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
            permissionLauncher.launch(perms)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("面对面快传") },
                navigationIcon = {
                    TextButton(onClick = { vm.stop(); onBack() }) { Text("返回") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.role == null) {
                IntroPanel(
                    supported = state.supported,
                    onHost = { requirePermission { vm.startHost() } },
                    onJoin = { requirePermission { vm.startJoin() } },
                    sessionCode = state.sessionCode,
                    onCodeChange = vm::setSessionCode,
                    onSelfTest = { vm.selfTest() }
                )
                return@Column
            }

            StatusCard(state)

            if (state.role == NearbyRole.HOST) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("怎么一个人发给一群人", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            "让每台接收的手机都打开「面对面快传 → 加入连接」，搜索设备后选择本机" +
                                (if (state.myDeviceName.isNotBlank()) "（${state.myDeviceName}）" else "") +
                                "，输入验证码 ${state.sessionCode}。Wi-Fi Direct 一般可同时连 4~8 台（看手机型号）。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "每台接收端各自点「开始互传」，就会来拉本机列表里的文件；他们同时拉也没问题" +
                                "（本机只上传一份数据流给每台设备，速度取决于 P2P 带宽）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("连接对方", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = state.sessionCode,
                            onValueChange = vm::setSessionCode,
                            label = { Text("对方的 4 位验证码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { requirePermission { vm.startJoin() } },
                                enabled = !state.connected,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (state.scanning) "搜索中…" else "搜索附近设备") }
                            OutlinedButton(
                                onClick = { requirePermission { vm.refreshPeers() } },
                                enabled = state.role == NearbyRole.JOIN && !state.connected,
                                modifier = Modifier.weight(1f)
                            ) { Text("重新搜索") }
                        }
                        if (state.connected && !state.isGroupOwner) {
                            Button(
                                onClick = { vm.startExchange() },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (state.busy) "互传中…" else "开始互传") }
                        }
                    }
                }
                if (!state.connected && state.peers.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                "搜到 ${state.peers.size} 台设备，点一下连接：",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            )
                            state.peers.forEach { d ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.connect(d) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.deviceName ?: "未知设备",
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(d.deviceAddress ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("连接", color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("添加要发的文件") }
                OutlinedButton(
                    onClick = { vm.stop() },
                    modifier = Modifier.weight(1f)
                ) { Text("断开/重来") }
            }

            if (state.localFiles.isNotEmpty()) {
                Text("待发送（${state.localFiles.size}）", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                state.localFiles.forEach { f ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (f.size >= 0) humanSize(f.size) else "大小未知",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.removeFile(f.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = "移除")
                            }
                        }
                    }
                }
            }

            if (state.transfers.isNotEmpty()) {
                Text("传输进度", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                state.transfers.forEach { t ->
                    val frac = if (t.total > 0) (t.done.toFloat() / t.total) else 0f
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Text(t.direction + "：" + t.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f))
                                Text("${(frac * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (state.received.isNotEmpty()) {
                Text("已收到（${state.received.size}）", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "位置：系统「下载 / 留学助手快传」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.received.forEach { name ->
                    Text("✅ " + name, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: NearbyUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.connected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    !state.supported -> "这台设备不支持 Wi-Fi Direct"
                    state.connected && state.isGroupOwner && state.peerCount > 0 ->
                        "已连接 ${state.peerCount} 台设备（可以同时来拉）"
                    state.connected && state.isGroupOwner -> "连接已就绪，等待设备加入"
                    state.connected && state.peerName.isNotBlank() -> "已连接：${state.peerName}"
                    state.connected -> "已连接"
                    state.role == NearbyRole.HOST -> "等待设备加入…"
                    else -> "还没连接"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (state.status.isNotBlank()) {
                Text(state.status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.role == NearbyRole.HOST && state.sessionCode.isNotBlank()) {
                Text("验证码：${state.sessionCode}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun IntroPanel(
    supported: Boolean,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    sessionCode: String,
    onCodeChange: (String) -> Unit,
    onSelfTest: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("完全离线 · Wi-Fi Direct 直连", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "两台手机都打开这个页面：一台点「创建连接」，另一台点「加入连接」并选中对方，" +
                    "输入对方屏幕上的 4 位验证码即可。连接后任意文件双向互传，不经过路由器、不费流量。" +
                    "收到的文件在系统「下载 / 留学助手快传」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!supported) {
                Text("⚠️ 这台设备可能不支持 Wi-Fi Direct",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onHost, modifier = Modifier.fillMaxWidth()) {
                Text("创建连接（让对方加入）")
            }
            OutlinedTextField(
                value = sessionCode,
                onValueChange = onCodeChange,
                label = { Text("加入时填对方的 4 位验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                Text("加入连接（搜索附近设备）")
            }
            if (BuildConfig.DEBUG) {
                // 没有第二台手机时，用本机自测确认传输协议是通的（release 不显示）
                TextButton(onClick = onSelfTest, modifier = Modifier.fillMaxWidth()) {
                    Text("本机自测（同一台手机跑服务端 + 客户端）",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
