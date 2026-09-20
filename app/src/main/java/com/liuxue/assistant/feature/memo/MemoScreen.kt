package com.liuxue.assistant.feature.memo

import android.app.DatePickerDialog
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.data.db.Memo
import com.liuxue.assistant.data.db.MemoAttachment
import com.liuxue.assistant.ui.ConfirmDeleteDialog
import com.liuxue.assistant.ui.WarnDialog
import com.liuxue.assistant.util.ShareUtils
import com.liuxue.assistant.util.DateUtils
import java.util.Calendar

/** 把时间戳归一到"当天 0 点"，方便按天比较 */
private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoScreen(onBack: (() -> Unit)? = null, vm: MemoViewModel = viewModel<MemoViewModel>()) {
    val all by vm.rows.collectAsState()
    val query by vm.query.collectAsState()
    var editing by remember { mutableStateOf<Memo?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Memo?>(null) }
    // 内置说明的只读查看
    var viewing by remember { mutableStateOf<Memo?>(null) }
    var viewingBody by remember { mutableStateOf("") }
    var showCalendar by remember { mutableStateOf(false) }
    var filterDay by remember { mutableStateOf<Long?>(null) }

    val rows = if (filterDay == null) all else all.filter { r ->
        dayStart(r.memo.createdAt) == filterDay ||
            (r.memo.remindAt?.let { dayStart(it) } == filterDay)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备忘录（${rows.size}）") },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                },
                actions = {
                    TextButton(onClick = { showCalendar = !showCalendar }) {
                        Text(if (showCalendar) "收起日历" else "日历")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索标题或正文") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )
            if (showCalendar) {
                MonthCalendar(all, filterDay) { filterDay = it }
            }
            filterDay?.let {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "只看 " + DateUtils.formatDay(it),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { filterDay = null }) { Text("清除") }
                }
            }
            Button(
                onClick = { editing = null; showEditor = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("新建备忘录")
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有备忘录\n正文加密保存，可设提醒、传附件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.memo.id }) { row ->
                        MemoCard(
                            row = row,
                            vm = vm,
                            onOpen = {
                                // 内置说明：只能只读查看；普通备忘：打开编辑
                                if (row.memo.title == com.liuxue.assistant.feature.about.WELCOME_MEMO_TITLE) {
                                    viewingBody = row.body
                                    viewing = row.memo
                                } else {
                                    editing = row.memo; showEditor = true
                                }
                            },
                            onPin = { vm.togglePin(row.memo) },
                            onEdit = { editing = row.memo; showEditor = true },
                            onDelete = {
                                // 内置的「使用说明与感谢」不允许删除
                                if (row.memo.title != com.liuxue.assistant.feature.about.WELCOME_MEMO_TITLE) {
                                    pendingDelete = row.memo
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    viewing?.let { m ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(m.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                ) {
                    Text(viewingBody, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("关闭") } }
        )
    }

    if (showEditor) {
        val initialBody = editing?.let { e -> all.firstOrNull { it.memo.id == e.id }?.body } ?: ""
        MemoEditorDialog(
            initial = editing,
            initialBody = initialBody,
            onDismiss = { showEditor = false },
            onSave = { title, body, remindAt, files ->
                vm.save(editing, title, body, remindAt, files) { showEditor = false }
            }
        )
    }
    pendingDelete?.let { m ->
        ConfirmDeleteDialog(
            text = "删除「" + m.title.ifBlank { "无标题" } + "」？附件会一起删。",
            onConfirm = { vm.delete(m) },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** 月历：有备忘录/提醒的日期打点；点某天只看那天 */
@Composable
private fun MonthCalendar(rows: List<MemoRow>, selected: Long?, onSelect: (Long?) -> Unit) {
    val now = remember { Calendar.getInstance() }
    var year by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(now.get(Calendar.MONTH)) }
    val marks = remember(rows) {
        rows.flatMap { r -> listOfNotNull(r.memo.createdAt, r.memo.remindAt) }
            .map { dayStart(it) }.toSet()
    }
    val today = dayStart(System.currentTimeMillis())
    val first = Calendar.getInstance().apply { set(year, month, 1, 0, 0, 0) }
    val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7   // 周一 = 0
    val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val weeks = (lead + days + 6) / 7

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (month == 0) { month = 11; year-- } else month--
            }) { Text("‹") }
            Text(
                "${year}年${month + 1}月",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = {
                if (month == 11) { month = 0; year++ } else month++
            }) { Text("›") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(
                    it, modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        repeat(weeks) { w ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { d ->
                    val num = w * 7 + d - lead + 1
                    val stamp = if (num in 1..days) {
                        Calendar.getInstance().apply { set(year, month, num, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                            .timeInMillis
                    } else 0L
                    val isSel = stamp != 0L && stamp == selected
                    Box(
                        Modifier.weight(1f).height(38.dp).padding(2.dp)
                            .background(
                                if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else if (stamp == today) MaterialTheme.colorScheme.surfaceVariant
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable(enabled = num in 1..days) {
                                onSelect(if (isSel) null else stamp)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (num in 1..days) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$num", style = MaterialTheme.typography.labelSmall)
                                if (stamp in marks) {
                                    Box(
                                        Modifier.size(4.dp)
                                            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MemoThumb(a: MemoAttachment, vm: MemoViewModel) {
    var bmp by remember(a.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(a.id) {
        vm.loadAttachmentBytes(a) { bytes ->
            bmp = bytes?.let { com.liuxue.assistant.ui.decodeThumbBytes(it) }
        }
    }
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Text(com.liuxue.assistant.ui.fileEmoji(a.mime, a.name),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MemoCard(
    row: MemoRow,
    vm: MemoViewModel,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var pendingDel by remember { mutableStateOf<MemoAttachment?>(null) }
    val m = row.memo
    // 内置的「使用说明与感谢」：不可置顶/编辑/删除，预览也只留一行，做成一个小条目
    val builtin = m.title == com.liuxue.assistant.feature.about.WELCOME_MEMO_TITLE
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onOpen() }
            ) {
                Text(
                    m.title.ifBlank { "无标题" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (builtin) {
                    Text("内置", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    IconButton(onClick = onPin) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "置顶",
                            tint = if (m.pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
            if (builtin) {
                Text(
                    "点这条可以查看全文（只读，不可修改）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (row.body.isNotBlank()) {
                Text(
                    row.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (builtin) 1 else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            m.remindAt?.let { at ->
                Text(
                    "⏰ " + DateUtils.formatDateTime(at) + " 提醒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            row.attachments.forEach { a ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).clickable {
                            ShareUtils.openStream(context, a.path, a.name, a.mime)
                        }
                    ) {
                        MemoThumb(a, vm)
                        Spacer(Modifier.size(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "%.1f KB".format(a.sizeBytes / 1024.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = {
                        ShareUtils.shareStream(context, a.path, a.name, a.mime)
                    }) { Text("分享", style = MaterialTheme.typography.labelSmall) }
                    IconButton(onClick = { pendingDel = a }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除附件")
                    }
                }
            }
        }
    }
    pendingDel?.let { a ->
        ConfirmDeleteDialog(
            text = "删除附件「" + a.name + "」？",
            onConfirm = { vm.deleteAttachment(a) },
            onDismiss = { pendingDel = null }
        )
    }
}

@Composable
private fun MemoEditorDialog(
    initial: Memo?,
    initialBody: String,
    onDismiss: () -> Unit,
    onSave: (String, String, Long?, List<MemoFile>) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var body by remember { mutableStateOf(initialBody) }
    var remindAt by remember { mutableStateOf(initial?.remindAt) }
    var showRemindPicker by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<MemoFile>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            var name = "file_" + System.currentTimeMillis()
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
                }
            }
            pending = pending + MemoFile(uri, name, mime)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建备忘录" else "编辑备忘录") },
        text = {
            Column(
                Modifier.height(380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("标题 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body, onValueChange = { body = it },
                    label = { Text("正文（加密）") },
                    modifier = Modifier.fillMaxWidth().height(130.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showRemindPicker = true }) {
                        Text(remindAt?.let { DateUtils.formatDay(it) } ?: "选择日期")
                    }
                    if (remindAt != null) {
                        TextButton(onClick = { remindAt = null }) { Text("清除") }
                    }
                }
                Text(
                    "当天 9 点后提醒（需通知权限）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { pick.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("添加附件（可多选）")
                }
                pending.forEachIndexed { i, f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.liuxue.assistant.ui.UriThumb(f.uri, f.mime, f.name, 40.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            f.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            pending = pending.filterIndexed { j, _ -> j != i }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "移除") }
                    }
                }
                if (initial != null && pending.isEmpty()) {
                    Text(
                        "已有附件在列表卡片里查看 / 删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) error = "请填写标题"
                else onSave(title, body, remindAt, pending)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showRemindPicker) {
        com.liuxue.assistant.ui.WheelDateDialog(
            title = "提醒日期",
            initialMillis = remindAt ?: System.currentTimeMillis(),
            onDismiss = { showRemindPicker = false },
            onPick = { remindAt = it; showRemindPicker = false }
        )
    }

    error?.let { WarnDialog(it) { error = null } }
}
