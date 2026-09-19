package com.liuxue.assistant.feature.translate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.data.ocr.OcrEngine
import com.liuxue.assistant.data.ocr.OcrModelRepository
import com.liuxue.assistant.data.ocr.OcrModels
import com.liuxue.assistant.data.ocr.OcrModelsStatus
import com.liuxue.assistant.data.ocr.OcrScript
import com.liuxue.assistant.data.translate.LlamaBridge
import com.liuxue.assistant.data.translate.ModelRepository
import com.liuxue.assistant.data.translate.ModelStatus
import com.liuxue.assistant.data.translate.TranslationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单次送进 GGUF 的最大字符数（JNI 的 n_batch=512，这里留足余量） */
private const val CHUNK_CHARS = 300

/** 翻译语言方向 */
data class LangPair(val from: String, val to: String)

data class TranslateUiState(
    val from: String = "ru",
    val to: String = "zh",
    val input: String = "",
    val output: String = "",
    val translating: Boolean = false,
    val message: String? = null,
    /** 模型是否可删除的确认 */
    val showDeleteConfirm: Boolean = false,

    // ---- 图片 / OCR ----
    /** 相册选中的图片（还没识别时为 null） */
    val imageUri: Uri? = null,
    /** OCR 正在识别 */
    val ocrBusy: Boolean = false,
    /** 识别结果信息（"识别到 3 行 · 检测 320ms · 识别 180ms"） */
    val ocrInfo: String? = null,
    /** 长文分段翻译进度（"正在翻译第 2/5 段…"） */
    val translateProgress: String? = null,
    /** 是否显示"删除 OCR 模型"确认 */
    val showOcrDeleteConfirm: Boolean = false,
    /** 识别语言：自动（中英/俄文自动判定）/ 中英 / 俄文 */
    val ocrScript: OcrScript = OcrScript.AUTO
)

/**
 * 离线翻译 + 离线 OCR 图片翻译。
 *
 * - 翻译模型 1.06 GB、OCR 模型约 16 MB，都**不打进 APK**，首次使用时下载到应用外部私有目录。
 * - OCR：相册选图 → PP-OCR（ONNX Runtime）识别 → 文本回填"原文" → 复用同一套 GGUF 翻译。
 */
class TranslateViewModel(app: Application) : AndroidViewModel(app) {

    private val modelRepo = ModelRepository(app)
    private val ocrRepo = OcrModelRepository(app)

    /** 推理引擎是否已加载模型（避免每次翻译都重新加载） */
    @Volatile private var engineReady = false

    val modelStatus: StateFlow<ModelStatus> = modelRepo.status

    /** OCR 模型状态（det / rec_ch / keys_ch） */
    val ocrStatus: StateFlow<OcrModelsStatus> = ocrRepo.status

    private val _ui = MutableStateFlow(TranslateUiState())
    val ui: StateFlow<TranslateUiState> = _ui.asStateFlow()

    /** 已下载的体积（用于显示"继续下载"） */
    val downloadedBytes = MutableStateFlow(0L)
    val ocrDownloadedBytes = MutableStateFlow(0L)

    init {
        modelRepo.refreshStatus()
        downloadedBytes.value = modelRepo.downloadedBytes()
        ocrRepo.refresh(OcrModels.BATCH1)
        ocrDownloadedBytes.value = ocrRepo.downloadedBytes(OcrModels.BATCH1)
    }

    fun refresh() {
        modelRepo.refreshStatus()
        downloadedBytes.value = modelRepo.downloadedBytes()
        ocrRepo.refresh(OcrModels.BATCH1)
        ocrDownloadedBytes.value = ocrRepo.downloadedBytes(OcrModels.BATCH1)
    }

    fun setFrom(code: String) { _ui.value = _ui.value.copy(from = code, output = "") }
    fun setTo(code: String) { _ui.value = _ui.value.copy(to = code, output = "") }
    fun swap() {
        val s = _ui.value
        _ui.value = s.copy(from = s.to, to = s.from, input = s.output, output = s.input)
    }
    fun setInput(v: String) { _ui.value = _ui.value.copy(input = v) }
    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }
    fun askDelete() { _ui.value = _ui.value.copy(showDeleteConfirm = true) }
    fun dismissDelete() { _ui.value = _ui.value.copy(showDeleteConfirm = false) }
    fun askDeleteOcr() { _ui.value = _ui.value.copy(showOcrDeleteConfirm = true) }
    fun dismissDeleteOcr() { _ui.value = _ui.value.copy(showOcrDeleteConfirm = false) }

    fun downloadModel() = viewModelScope.launch {
        modelRepo.download()
        downloadedBytes.value = modelRepo.downloadedBytes()
    }

    fun cancelDownload() {
        modelRepo.cancel()
    }

    // ==================== OCR（图片翻译）====================

    fun setImage(uri: Uri?) {
        _ui.value = _ui.value.copy(imageUri = uri, ocrInfo = null, output = "")
    }

    fun setOcrScript(script: OcrScript) {
        _ui.value = _ui.value.copy(ocrScript = script)
    }

    fun clearImage() {
        _ui.value = _ui.value.copy(imageUri = null, ocrInfo = null)
    }

    fun downloadOcrModels() = viewModelScope.launch {
        ocrRepo.download(OcrModels.BATCH1)
        ocrDownloadedBytes.value = ocrRepo.downloadedBytes(OcrModels.BATCH1)
    }

    fun cancelOcrDownload() {
        ocrRepo.cancel()
    }

    fun deleteOcrModels() {
        OcrEngine.release()
        ocrRepo.deleteAll()
        ocrDownloadedBytes.value = 0L
        _ui.value = _ui.value.copy(showOcrDeleteConfirm = false, message = "OCR 模型已删除")
    }

    /**
     * 一步到位：识别相册图 → 回填原文 → 自动翻译。
     * OCR 模型或翻译模型没准备好时给出明确提示。
     */
    fun recognizeAndTranslate() = viewModelScope.launch {
        val s = _ui.value
        val uri = s.imageUri
        if (uri == null) {
            _ui.value = s.copy(message = "请先从相册选一张图片")
            return@launch
        }
        if (s.ocrBusy) return@launch
        if (!ocrRepo.batch1Ready()) {
            _ui.value = s.copy(
                message = "OCR 模型还没准备好，请先下载（约 " +
                    OcrModels.humanSize(OcrModels.BATCH1.sumOf { it.expectedSize }) + "）"
            )
            return@launch
        }

        _ui.value = s.copy(ocrBusy = true, message = null, ocrInfo = null)

        val bmp = withContext(Dispatchers.IO) { decodeForOcr(uri) }
        if (bmp == null) {
            _ui.value = _ui.value.copy(ocrBusy = false, message = "图片读取失败，请换一张试试")
            return@launch
        }

        val loaded = withContext(Dispatchers.IO) {
            OcrEngine.load(
                detPath = ocrRepo.path(OcrModels.DET_SPEC),
                recPath = ocrRepo.path(OcrModels.REC_CH_SPEC),
                keysPath = ocrRepo.path(OcrModels.KEYS_CH_SPEC),
                recRuPath = ocrRepo.path(OcrModels.REC_RU_SPEC),
                keysRuPath = ocrRepo.path(OcrModels.DICT_RU_SPEC)
            )
        }
        if (!loaded) {
            _ui.value = _ui.value.copy(
                ocrBusy = false,
                message = "OCR 引擎加载失败（模型可能损坏，删除后重新下载即可）"
            )
            return@launch
        }

        val script = s.ocrScript
        val result = runCatching { withContext(Dispatchers.IO) { OcrEngine.recognize(bmp, script) } }
            .getOrElse {
                _ui.value = _ui.value.copy(ocrBusy = false, message = "识别出错：" + (it.message ?: "未知错误"))
                return@launch
            }

        if (result.fullText.isBlank()) {
            _ui.value = _ui.value.copy(
                ocrBusy = false,
                message = "没有识别到文字（换清晰一点、正着拍的照片试试）"
            )
            return@launch
        }

        val info = "识别到 " + result.boxCount + " 行 · 检测 " + result.detMs + "ms · 识别 " + result.recMs + "ms"
        _ui.value = _ui.value.copy(ocrBusy = false, input = result.fullText, ocrInfo = info, message = null)

        // 识别完直接复用现有 GGUF 翻译
        translate()
    }

    /** 中断正在进行的翻译 */
    fun cancelTranslate() {
        LlamaBridge.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        LlamaBridge.release()
        engineReady = false
    }

    fun deleteModel() {
        LlamaBridge.release()
        engineReady = false
        modelRepo.delete()
        downloadedBytes.value = 0L
        _ui.value = _ui.value.copy(
            showDeleteConfirm = false,
            message = "模型已删除，释放了 " + TranslationModel.humanSize(TranslationModel.EXPECTED_SIZE)
        )
    }

    /**
     * 执行翻译。
     * 注意：推理引擎接入后这里改为调用引擎；未就绪时给出明确提示。
     */
    fun translate() = viewModelScope.launch {
        val s = _ui.value
        if (s.input.isBlank()) {
            _ui.value = s.copy(message = "请输入要翻译的内容")
            return@launch
        }
        if (modelStatus.value !is ModelStatus.Ready) {
            _ui.value = s.copy(message = "翻译模型还没准备好，请先下载模型")
            return@launch
        }
        val model = modelStatus.value as? ModelStatus.Ready ?: return@launch
        _ui.value = s.copy(translating = true, message = null)

        // 首次使用时把模型加载进内存（数秒），之后复用
        if (!engineReady) {
            val ok = LlamaBridge.init(model.path)
            if (!ok) {
                _ui.value = _ui.value.copy(
                    translating = false,
                    message = "模型加载失败，请确认文件完整（可删除后重新下载）"
                )
                return@launch
            }
            engineReady = true
        }

        val result = translateText(s.input.trim(), s.to)
        _ui.value = _ui.value.copy(
            translating = false,
            translateProgress = null,
            output = result.ifBlank { "" },
            message = if (result.isBlank()) {
                "翻译失败或结果为空，请重试"
            } else null
        )
    }

    /**
     * 翻译长文本：**按行分段**逐段翻译后拼接。
     *
     * 为什么必须分段：JNI 里 `llama_batch_get_one(tokens, nTokens)` 一次解码整个 prompt，
     * 而 context 的 `n_batch = 512`；prompt 超过 512 token 时 `llama_decode` 直接失败、
     * 返回空串（不报错）。OCR 出来的整页文字很容易超过 512 token，所以在这里切开，
     * 每段控制在 [CHUNK_CHARS] 字符以内（最坏情况也远小于 512 token）。
     */
    private suspend fun translateText(text: String, to: String): String {
        val sys = LlamaBridge.systemPrompt(to)
        if (text.length <= CHUNK_CHARS) return LlamaBridge.translate(sys, text)
        val chunks = splitChunks(text, CHUNK_CHARS)
        val sb = StringBuilder()
        chunks.forEachIndexed { i, chunk ->
            if (sb.isNotEmpty()) sb.append(System.lineSeparator())
            _ui.value = _ui.value.copy(translateProgress = "正在翻译第 " + (i + 1) + "/" + chunks.size + " 段…")
            val r = LlamaBridge.translate(sys, chunk)
            sb.append(r.trim())
        }
        return sb.toString()
    }

    /** 按行分段（单行超长时硬切），保证每段 <= [limit] 字符 */
    private fun splitChunks(text: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            var cur = line
            while (cur.length > limit) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                out.add(cur.substring(0, limit))
                cur = cur.substring(limit)
            }
            if (sb.isNotEmpty() && sb.length + cur.length + 1 > limit) {
                out.add(sb.toString()); sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(cur)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.ifEmpty { listOf(text) }
    }

    // ==================== 图片解码 ====================

    /** 解码相册图片：下采样到长边 ≤ 2048 并应用 EXIF 旋转，避免大图 OOM / 方向错误 */
    private fun decodeForOcr(uri: Uri): Bitmap? = runCatching {
        val cr = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = runCatching {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        bmp
    }.getOrNull()
}
