package com.liuxue.assistant.feature.files

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.data.db.VaultAttachment
import com.liuxue.assistant.domain.ExpiryLevel
import com.liuxue.assistant.domain.VaultCategory
import com.liuxue.assistant.ui.ConfirmDeleteDialog
import com.liuxue.assistant.util.DateUtils
import com.liuxue.assistant.util.ShareUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailScreen(
    fileId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    vm: FileDetailViewModel = viewModel<FileDetailViewModel>()
) {
    val state by vm.state.collectAsState(initial = DetailState())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingDeleteAtt by remember { mutableStateOf<VaultAttachment?>(null) }
    var shareTarget by remember { mutableStateOf<VaultAttachment?>(null) }
    // 图片放大查看
    var showCover by remember { mutableStateOf(false) }
    var viewerBytes by remember { mutableStateOf<ByteArray?>(null) }
    var viewerTitle by remember { mutableStateOf("") }

    LaunchedEffect(fileId) { vm.load(fileId) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    val pickAnyFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            val (name, mime) = queryMeta(context, uri)
            vm.addAttachment(fileId, uri, name, mime)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.item?.title ?: "详情") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    IconButton(onClick = { vm.togglePin() }) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "置顶",
                            tint = if (state.item?.pinned == true)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    IconButton(onClick = { onEdit(fileId) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val item = state.item
        if (item == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(if (state.loading) "载入中…" else "记录不存在")
            }
            return@Scaffold
        }

        val coverBmp = state.cover?.let { bytes ->
            remember(bytes) {
                runCatching {
                    android.graphics.BitmapFactory
                        .decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }.getOrNull()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val cat = VaultCategory.fromKey(item.category)
            val level = ExpiryLevel.of(item.expireDate, item.remindDaysBefore)

            coverBmp?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showCover = true },
                    contentScale = ContentScale.Crop
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = level.color.copy(alpha = 0.12f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = level.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = level.color
                    )
                    if (item.expireDate != null) {
                        Text(
                            "到期日 " + DateUtils.formatDay(item.expireDate) +
                                " · " + DateUtils.humanRemaining(item.expireDate),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (item.remindDaysBefore < 0) "未开启提醒"
                            else "提前 " + item.remindDaysBefore + " 天提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("未设置到期日", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            InfoRow("分类", cat.emoji + " " + cat.label)
            InfoRow("签发日期", DateUtils.formatDay(item.issueDate))
            item.note.takeIf { it.isNotBlank() }?.let { InfoRow("备注", it) }
            InfoRow("录入时间", DateUtils.formatDateTime(item.createdAt))

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "附件（" + state.attachments.size + "）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { pickAnyFile.launch(arrayOf("image/*", "application/pdf", "*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("添加（可多选）")
                }
            }

            if (state.attachments.isEmpty()) {
                Text(
                    "可添加多张照片或 PDF 等文件，同样会加密保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.attachments.forEach { att ->
                    AttachmentRow(
                        att = att,
                        onShare = { ShareUtils.shareStream(context, att.path, att.name, att.mime) },
                        onDelete = { pendingDeleteAtt = att },
                        loadBytes = { cb -> scope.launch { cb(vm.attachmentBytes(att)) } },
                        onOpen = {
                            if (att.mime.startsWith("image/")) {
                                scope.launch {
                                    vm.attachmentBytes(att)?.let { b ->
                                        viewerBytes = b
                                        viewerTitle = att.name
                                    }
                                }
                            } else {
                                ShareUtils.openStream(context, att.path, att.name, att.mime)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (item.coverPath != null) {
                Button(
                    onClick = {
                        scope.launch {
                            val f = vm.prepareForShare(item.coverPath!!, item.title + ".jpg")
                            if (f != null) ShareUtils.shareFile(context, f, "image/jpeg", "分享证件图片")
                            else snackbar.showSnackbar("解密失败")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("分享证件图片（解密后分享）")
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showCover && coverBmp != null) {
            ZoomableImageDialog(coverBmp, item.title) { showCover = false }
        }
        viewerBytes?.let { bytes ->
            ZoomableImageBytesDialog(bytes, viewerTitle) { viewerBytes = null }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("加密图片与全部附件会一并删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    pendingDeleteAtt?.let { att ->
        ConfirmDeleteDialog(
            text = "删除附件「" + att.name + "」？",
            onConfirm = { vm.deleteAttachment(att) },
            onDismiss = { pendingDeleteAtt = null }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 84.dp, height = 22.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AttachmentRow(
    att: VaultAttachment,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)? = null,
    loadBytes: ((ByteArray?) -> Unit) -> Unit = {}
) {
    var thumb by remember(att.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(att.id) { loadBytes { thumb = it } }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = onOpen != null) { onOpen?.invoke() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.liuxue.assistant.ui.BytesThumb(thumb, att.mime, att.name, 44.dp)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(att.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatSize(att.sizeBytes) + " · 加密存储",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "分享")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

/** 从 content Uri 读取文件名与 MIME */
private fun queryMeta(context: android.content.Context, uri: Uri): Pair<String, String> {
    var name = "attachment_" + System.currentTimeMillis()
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                c.getString(idx)?.let { name = it }
            }
        }
    }
    return name to mime
}
