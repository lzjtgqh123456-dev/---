package com.liuxue.assistant.feature.files

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.domain.VaultCategory
import com.liuxue.assistant.ui.WarnDialog
import com.liuxue.assistant.util.DateUtils
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditScreen(
    fileId: Long,
    onBack: () -> Unit,
    vm: FileEditViewModel = viewModel<FileEditViewModel>()
) {
    val state by vm.state.collectAsState(initial = EditState())
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(fileId) { vm.load(fileId) }

    // 相册选图
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(vm::setCover)
        }
    }

    // 拍照
    var captureFile by remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) captureFile?.let(vm::setCoverFromFile)
    }

    var showExpirePicker by remember { mutableStateOf(false) }
    var showIssuePicker by remember { mutableStateOf(false) }
    var showRemindPicker by remember { mutableStateOf(false) }

    // 附件：一次可选多个任意类型文件，保存时一起加密入库
    var pending by remember { mutableStateOf<List<NewDocFile>>(emptyList()) }
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            var name = "file_" + System.currentTimeMillis()
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
                }
            }
            pending = pending + NewDocFile(uri, name, mime)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (fileId == 0L) "添加证件" else "编辑证件") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("取消") }
                },
                actions = {
                    TextButton(
                        onClick = { vm.save(pending, onBack) },
                        enabled = !state.loading
                    ) { Text(if (state.loading) "保存中" else "保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoverPicker(
                bytes = state.coverBytes,
                onPickGallery = { pickImage.launch("image/*") },
                onTakePhoto = {
                    val dir = File(context.cacheDir, "capture").apply { mkdirs() }
                    val f = File(dir, "cap_" + System.currentTimeMillis() + ".jpg")
                    captureFile = f
                    val uri = FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", f
                    )
                    takePhoto.launch(uri)
                },
                onClear = vm::clearCover
            )

            OutlinedTextField(
                value = state.title,
                onValueChange = vm::setTitle,
                label = { Text("名称 *") },
                placeholder = { Text("如：护照、学生证") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 分类下拉
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = VaultCategory.fromKey(state.category).let { it.emoji + " " + it.label },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("分类") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    VaultCategory.entries.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.emoji + " " + cat.label) },
                            onClick = {
                                vm.setCategory(cat.key)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DateField(
                    label = "签发日期",
                    value = state.issueDate,
                    modifier = Modifier.weight(1f),
                    onClick = { showIssuePicker = true }
                )
                DateField(
                    label = "到期日期",
                    value = state.expireDate,
                    modifier = Modifier.weight(1f),
                    onClick = { showExpirePicker = true }
                )
            }

            Text("到期提前提醒", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { showRemindPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.remindDaysBefore <= 0) "不提醒"
                    else "提前 " + state.remindDaysBefore + " 天"
                )
            }
            if (state.expireDate != null) {
                val level = com.liuxue.assistant.domain.ExpiryLevel.of(
                    state.expireDate, state.remindDaysBefore
                )
                Text(
                    text = "当前状态：" + level.label + "（" +
                        DateUtils.humanRemaining(state.expireDate!!) + "）",
                    style = MaterialTheme.typography.bodySmall,
                    color = level.color
                )
            }

            OutlinedTextField(
                value = state.note,
                onValueChange = vm::setNote,
                label = { Text("备注") },
                placeholder = { Text("证件号码、签发机关、存放位置……") },
                modifier = Modifier.fillMaxWidth().height(110.dp)
            )

            state.error?.let { msg -> WarnDialog(msg) { vm.clearError() } }

            Text("附件（可多选）", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { pickFiles.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("添加文件（可多选）")
            }
            pending.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.liuxue.assistant.ui.UriThumb(f.uri, f.mime, f.name, 40.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        (if (f.mime.startsWith("image/")) "🖼 " else "📎 ") + f.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        pending = pending.filterIndexed { j, _ -> j != i }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "移除")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.save(pending, onBack) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading
            ) { Text("保存") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showIssuePicker) {
        DatePickerSheet(
            initial = state.issueDate,
            onDismiss = { showIssuePicker = false },
            onPicked = {
                vm.setIssueDate(it)
                showIssuePicker = false
            }
        )
    }
    if (showExpirePicker) {
        DatePickerSheet(
            initial = state.expireDate,
            onDismiss = { showExpirePicker = false },
            onPicked = {
                vm.setExpireDate(it)
                showExpirePicker = false
            }
        )
    }
    if (showRemindPicker) {
        com.liuxue.assistant.ui.WheelNumberDialog(
            title = "提前几天提醒",
            range = 0..90,
            initial = if (state.remindDaysBefore <= 0) 0 else state.remindDaysBefore,
            unit = "天（0 = 不提醒）",
            onDismiss = { showRemindPicker = false },
            onPick = {
                vm.setRemindDays(if (it == 0) -1 else it)
                showRemindPicker = false
            }
        )
    }
}

@Composable
private fun CoverPicker(
    bytes: ByteArray?,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onClear: () -> Unit
) {
    val bitmap = remember(bytes) {
        bytes?.let {
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "证件图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            IconButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Filled.Clear, contentDescription = "移除图片")
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("添加证件照片", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTakePhoto) { Text("拍照") }
                    OutlinedButton(onClick = onPickGallery) { Text("从相册选") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "图片会 AES-256 加密后存在本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DateField(
    label: String,
    value: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(modifier) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                text = if (value == null) label else DateUtils.formatDay(value),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: Long?,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit
) {
    com.liuxue.assistant.ui.WheelDateDialog(
        title = "选择日期",
        initialMillis = initial ?: System.currentTimeMillis(),
        onDismiss = onDismiss,
        onPick = onPicked
    )
}
