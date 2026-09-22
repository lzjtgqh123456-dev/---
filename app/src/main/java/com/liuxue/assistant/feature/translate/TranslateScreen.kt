package com.liuxue.assistant.feature.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.data.ocr.OcrModels
import com.liuxue.assistant.data.ocr.OcrModelsStatus
import com.liuxue.assistant.data.ocr.OcrScript
import com.liuxue.assistant.data.translate.ModelStatus
import com.liuxue.assistant.data.translate.TranslationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线翻译 + 图片翻译（离线 OCR）。
 *
 * 模型都不打进 APK，首次使用时下载：翻译模型 1.06 GB，OCR 模型约 16 MB。
 * 本页同时承担"说明"职责，避免用户对下载体积感到困惑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(vm: TranslateViewModel = viewModel<TranslateViewModel>()) {
    val state by vm.ui.collectAsState(initial = TranslateUiState())
    val status by vm.modelStatus.collectAsState(initial = ModelStatus.NotDownloaded)
    val ocrState by vm.ocrStatus.collectAsState(initial = OcrModelsStatus.Missing(emptyList(), 0L, 0L))
    val downloaded by vm.downloadedBytes.collectAsState(initial = 0L)
    val ocrDownloaded by vm.ocrDownloadedBytes.collectAsState(initial = 0L)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("离线翻译") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // 键盘弹出时把内容顶到键盘上方：否则「原文」输入框和「翻译」按钮会被键盘整个盖住
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModelStatusCard(status, downloaded, vm)
            OcrImageCard(state, ocrState, ocrDownloaded, vm)
            TranslationCard(state, vm)
            if (SHOW_TUTORIAL) {
                ModelInfoCard()
                OcrInfoCard()
            }
        }
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissDelete() },
            title = { Text("删除离线模型？") },
            text = {
                Text(
                    "将释放约 " + TranslationModel.humanSize(TranslationModel.EXPECTED_SIZE) +
                        " 空间。删除后需要重新下载才能离线翻译。"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteModel() }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissDelete() }) { Text("取消") }
            }
        )
    }

    if (state.showOcrDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissDeleteOcr() },
            title = { Text("删除 OCR 模型？") },
            text = { Text("将释放约 " + OcrModels.humanSize(OcrModels.BATCH1.sumOf { it.expectedSize }) +
                " 空间。删除后需要重新下载才能识别图片文字。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteOcrModels() }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissDeleteOcr() }) { Text("取消") }
            }
        )
    }
}

// ==================== 翻译模型状态卡（首次下载入口）====================

@Composable
private fun ModelStatusCard(status: ModelStatus, downloaded: Long, vm: TranslateViewModel) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                is ModelStatus.Ready -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                is ModelStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("离线翻译模型", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (status is ModelStatus.Ready) {
                    IconButton(onClick = { vm.askDelete() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除模型")
                    }
                }
            }

            when (status) {
                is ModelStatus.NotDownloaded -> {
                    val partial = if (downloaded > 0) downloaded else 0L
                    Text(
                        "首次使用需要下载模型（约 " +
                            TranslationModel.humanSize(TranslationModel.EXPECTED_SIZE) + "）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (partial > 0) {
                        Text(
                            "已下载 " + TranslationModel.humanSize(partial) + "，可继续下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Button(
                        onClick = { vm.downloadModel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (partial > 0) "继续下载模型" else "下载模型")
                    }
                }

                is ModelStatus.Downloading -> {
                    Text(
                        "正在下载… " + status.percent + "%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { status.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val speed = if (status.bytesPerSec > 0)
                        TranslationModel.humanSize(status.bytesPerSec) + "/s" else "…"
                    val eta = if (status.bytesPerSec > 0 && status.total > status.downloaded) {
                        val sec = (status.total - status.downloaded) / status.bytesPerSec
                        " · 剩余约 " + (sec / 60 + 1) + " 分钟"
                    } else ""
                    Text(
                        TranslationModel.humanSize(status.downloaded) + " / " +
                            TranslationModel.humanSize(status.total) + " · " + speed + eta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { vm.cancelDownload() },
                        modifier = Modifier.fillMaxWidth()) { Text("取消下载") }
                }

                is ModelStatus.Ready -> {
                    Text("✅ 模型已就绪，可离线翻译",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(
                        "占用 " + TranslationModel.humanSize(status.sizeBytes) +
                            " · 存放于应用私有目录（卸载即清理）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is ModelStatus.Failed -> {
                    Text("下载失败", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text(status.message, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text("重新检查")
                    }
                }
            }
        }
    }
}

// ==================== 图片翻译（离线 OCR）====================

@Composable
private fun OcrImageCard(
    state: TranslateUiState,
    ocrStatus: OcrModelsStatus,
    ocrDownloaded: Long,
    vm: TranslateViewModel
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setImage(uri)
    }
    val ocrReady = ocrStatus is OcrModelsStatus.Ready
    val busy = state.ocrBusy || state.translating

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图片翻译（离线 OCR）", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (ocrReady) {
                    IconButton(onClick = { vm.askDeleteOcr() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除 OCR 模型")
                    }
                }
            }

            // ---- 选图 / 换图 ----
            if (state.imageUri == null) {
                Button(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🖼 从相册选图") }
            } else {
                UriPreview(state.imageUri, Modifier.fillMaxWidth().height(180.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("换一张") }
                    OutlinedButton(
                        onClick = { vm.clearImage() },
                        modifier = Modifier.weight(1f)
                    ) { Text("移除图片") }
                }
            }

            // ---- 识别语言（中英 / 俄文 / 自动）----
            Text("识别语言", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(OCR_SCRIPTS) { (label, value) ->
                    FilterChip(
                        selected = state.ocrScript == value,
                        onClick = { vm.setOcrScript(value) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // ---- OCR 模型状态 ----
            when (ocrStatus) {
                is OcrModelsStatus.Missing -> {
                    Text(
                        "首次使用需要下载 OCR 模型（约 " +
                            OcrModels.humanSize(OcrModels.BATCH1.sumOf { it.expectedSize }) +
                            "，含文本检测 + 中英识别 + 俄文识别）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (ocrDownloaded > 0) {
                        Text("已下载 " + OcrModels.humanSize(ocrDownloaded) + "，可继续下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Button(onClick = { vm.downloadOcrModels() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ocrDownloaded > 0) "继续下载 OCR 模型" else "下载 OCR 模型")
                    }
                }

                is OcrModelsStatus.Downloading -> {
                    Text("正在下载 " + ocrStatus.label + "（" +
                        (ocrStatus.fileIndex + 1) + "/" + ocrStatus.fileCount + "）… " +
                        ocrStatus.percent + "%",
                        style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { ocrStatus.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val speed = if (ocrStatus.bytesPerSec > 0)
                        OcrModels.humanSize(ocrStatus.bytesPerSec) + "/s" else "…"
                    Text(
                        OcrModels.humanSize(ocrStatus.fileDone) + " / " +
                            OcrModels.humanSize(ocrStatus.fileTotal) + " · " + speed,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { vm.cancelOcrDownload() },
                        modifier = Modifier.fillMaxWidth()) { Text("取消下载") }
                }

                is OcrModelsStatus.Ready -> {
                    Text("✅ OCR 模型已就绪（" + OcrModels.humanSize(ocrStatus.bytes) + "）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }

                is OcrModelsStatus.Failed -> {
                    Text("OCR 模型下载失败", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text(ocrStatus.message, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text("重新检查")
                    }
                }
            }

            // ---- 识别并翻译 ----
            if (state.imageUri != null) {
                Button(
                    onClick = { vm.recognizeAndTranslate() },
                    enabled = !busy && ocrReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            state.ocrBusy -> "识别中…"
                            state.translating -> "翻译中…"
                            else -> "识别并翻译"
                        }
                    )
                }
                if (!ocrReady && ocrStatus !is OcrModelsStatus.Downloading) {
                    Text("下载完 OCR 模型后即可识别", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            state.ocrInfo?.let {
                Text("🔍 " + it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 页内教程卡片总开关（内容已统一搬到「感谢与教程」文档） */
private const val SHOW_TUTORIAL = false

/**
 * 识别语言选项 —— 写的必须就是"这个模型/模式实际认识什么"，别用含糊的"中英"。
 * - CH：中文识别模型（PP-OCRv4），词表里同时有汉字和拉丁字母 ⇒ 中文 + 英文
 * - RU：西里尔字母模型 ⇒ 俄文（以及其它西里尔文字）
 * - AUTO：前 1~2 行同时跑上面两个模型，整体得分高的那个胜出；之后逐行用胜出模型，
 *         某一行得分太低再兜底试另一个 ⇒ 中俄混排也能处理
 */
private val OCR_SCRIPTS = listOf(
    "自动判断（中／英／俄）" to OcrScript.AUTO,
    "中文 + 英文" to OcrScript.CH,
    "俄文（西里尔）" to OcrScript.RU
)

/** 相册图片预览（下采样解码，避免大图 OOM） */
@Composable
private fun UriPreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val cr = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var s = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / s > 720) s *= 2
                val o = BitmapFactory.Options().apply { inSampleSize = s }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            }.getOrNull()
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b == null) {
            Text("加载中…", style = MaterialTheme.typography.labelSmall)
        } else {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "待识别的图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
        }
    }
}

// ==================== 翻译区 ====================

@Composable
private fun TranslationCard(state: TranslateUiState, vm: TranslateViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语言方向", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.swap() }) { Text("互换") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TranslationModel.SUPPORTED_LANGUAGES) { pair ->
                    FilterChip(
                        selected = state.from == pair.second,
                        onClick = { vm.setFrom(pair.second) },
                        label = { Text(pair.first, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Text("译成", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TranslationModel.SUPPORTED_LANGUAGES) { pair ->
                    FilterChip(
                        selected = state.to == pair.second,
                        onClick = { vm.setTo(pair.second) },
                        label = { Text(pair.first, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = state.input,
                onValueChange = { vm.setInput(it) },
                label = { Text("原文") },
                placeholder = { Text("粘贴或输入要翻译的句子，也可以选图片自动识别") },
                minLines = 3,
                // 键盘右下角直接是「完成」= 开始翻译，省得被键盘挡住按钮时还得先收键盘
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.translate() }),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.translate() },
                enabled = !state.translating,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.translating) "翻译中…" else "翻译") }

            state.translateProgress?.let {
                Text("⏳ " + it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            if (state.output.isNotBlank()) {
                Text("译文", style = MaterialTheme.typography.labelMedium)
                Text(state.output, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ==================== 说明 ====================

@Composable
private fun OcrInfoCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("关于图片翻译", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            InfoLine("怎么用", "从相册选一张带文字的图片 → 点「识别并翻译」→ 自动填好原文并翻译成目标语言")
            InfoLine("OCR 引擎", "PP-OCR（PP-OCRv4 检测 + 中英识别），ONNX Runtime 在手机本地推理")
            InfoLine("OCR 模型", "约 " + OcrModels.humanSize(OcrModels.BATCH1.sumOf { it.expectedSize }) +
                "，首次使用时下载；下载源 hf-mirror → HuggingFace，支持断点续传")
            InfoLine("隐私", "图片和文字都在本机处理，不会上传到任何服务器；识别和翻译都不需要联网")
            InfoLine("小技巧", "尽量拍正、光线均匀、文字占画面比例大一些，识别更准；识别后的原文可以直接改，再点「翻译」")
        }
    }
}

@Composable
private fun ModelInfoCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("关于离线翻译", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)

            InfoLine("模型", "腾讯混元 HY-MT1.5-1.8B（" + TranslationModel.QUANT + " 量化）")
            InfoLine("体积", TranslationModel.humanSize(TranslationModel.EXPECTED_SIZE) + "，首次使用时下载")
            InfoLine("为什么不在安装包里",
                "模型 1.06 GB，打进安装包会让 App 变成 1 GB 以上；" +
                    "改成用到时再下载，安装包保持轻量，不用翻译功能的用户不必占用这份空间。")
            InfoLine("下载源", "优先国内镜像 hf-mirror，失败自动切换 HuggingFace 官方；支持断点续传")
            InfoLine("存放位置", "应用私有目录，卸载 App 即自动清理，不需要任何存储权限")
            InfoLine("内存要求", "建议 " + TranslationModel.MIN_RAM_MB + " MB 以上可用内存")
            InfoLine("隐私", "翻译全在本机完成，原文不会上传到任何服务器；无需联网即可使用（除首次下载）")

            HorizontalDivider()
            Text("支持的语言", style = MaterialTheme.typography.labelLarge)
            Text(
                TranslationModel.SUPPORTED_LANGUAGES.joinToString("、") { it.first },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
