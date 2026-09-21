package com.liuxue.assistant.feature.net

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import com.liuxue.assistant.data.db.AiQa
import com.liuxue.assistant.data.net.AiHistoryRepository
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.data.net.AiAttachment
import com.liuxue.assistant.data.net.AiPreset
import com.liuxue.assistant.data.net.AiRepository
import com.liuxue.assistant.data.net.ChatMessage
import com.liuxue.assistant.data.net.ExchangeRepository
import com.liuxue.assistant.data.net.NetConfig
import com.liuxue.assistant.data.net.RateTable
import com.liuxue.assistant.data.net.SearchEngine
import com.liuxue.assistant.data.net.SearchRepository
import com.liuxue.assistant.data.net.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NetTab(val label: String) {
    QUICK("AI 搜索"), RATE("汇率"), SETTINGS("设置")
}

data class NetUiState(
    val tab: NetTab = NetTab.QUICK,
    // 搜索
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val searchErrors: List<String> = emptyList(),
    val searchedQuery: String = "",
    /** 快捷 AI 搜索：开则先联网检索，再把资料喂给 AI */
    val webSearch: Boolean = false,
    /** 深度思考：更长系统提示 + 低温度 + 更大输出预算 */
    val deepThink: Boolean = false,
    // 汇率
    val rateBase: String = "CNY",
    val rateFrom: String = "USD",
    val rateTo: String = "CNY",
    val rateAmount: String = "100",
    val rateTable: RateTable? = null,
    val rateLoading: Boolean = false,
    val rateResult: String = "",
    // AI
    val aiInput: String = "",
    val aiBusy: Boolean = false,
    val aiAnswer: String = "",
    val aiHistory: List<ChatMessage> = emptyList(),
    /** 历史问答（持久化，可点回去） */
    val qaHistory: List<AiQa> = emptyList(),
    val showHistory: Boolean = false,
    /** 当前选中的预设；[NO_PRESET] 表示不用任何预设（默认） */
    val selectedPresetId: Long = NO_PRESET,
    val presets: List<AiPreset> = emptyList(),
    /** 本次提问要带的附件 */
    val attachments: List<PendingAttachment> = emptyList(),
    /** 正在测试 AI 接口连通性 */
    val aiTesting: Boolean = false,
    /** 上一次测试的结果（直接显示在设置页，不只在 snackbar） */
    val aiTestResult: String = "",
    val message: String? = null
)

/** 表示「不用任何预设」 */
const val NO_PRESET = 0L

/** 待发送的附件（选中时只存 uri/名字/大小，点发送时才真正读内容） */
data class PendingAttachment(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long
)

/**
 * P5：多引擎搜索 / 汇率 / 自定义 API AI。
 *
 * 默认零配置可用：DuckDuckGo + 维基百科 + 汇率都不需要任何 Key。
 * 想用 Google/Bing/Serper/Brave 或自建 SearXNG，到「设置」里填 Key 即可。
 */
class NetViewModel(app: Application) : AndroidViewModel(app) {

    private val config = NetConfig(app)
    private val searchRepo = SearchRepository(config)
    private val rateRepo = ExchangeRepository()
    private val aiRepo = AiRepository(app, config)
    private val aiHistoryRepo = AiHistoryRepository(app)

    private val _ui = MutableStateFlow(NetUiState())
    val ui: StateFlow<NetUiState> = _ui.asStateFlow()

    /** 自定义预设存在 SharedPreferences（用户可加） */
    private val presetSp = app.getSharedPreferences("ai_presets", 0)

    @Volatile private var pdfBoxReady = false

    companion object {
        /** 单个附件大小上限（只是防呆；各类型都是流式读取，不会再整个读进内存） */
        private const val MAX_ATTACH_BYTES = 200L * 1024 * 1024
        private const val MAX_ATTACH_CHARS = 20_000
        private const val VISION_MAX_SIDE = 1280
        private const val MAX_PDF_PAGES = 30
        private const val MAX_ZIP_ENTRY_BYTES = 4 * 1024 * 1024
        private const val MAX_SLIDES = 100
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic")
        private val TEXT_EXT = setOf(
            "txt", "md", "csv", "json", "xml", "log", "kt", "java", "py", "js", "ts",
            "html", "htm", "yml", "yaml", "ini", "sql", "sh", "bat", "gradle", "properties", "srt", "vtt"
        )
    }

    init {
        loadPresets()
        _ui.value = _ui.value.copy(rateBase = config.baseCurrency)
        viewModelScope.launch {
            aiHistoryRepo.observeAll().collect { list ->
                _ui.value = _ui.value.copy(qaHistory = list)
            }
        }
    }

    // ---------- AI 问答历史 ----------

    fun toggleHistory() { _ui.value = _ui.value.copy(showHistory = !_ui.value.showHistory) }

    /** 点历史：回到当时的问题 + 回答 */
    fun openHistory(item: AiQa) {
        _ui.value = _ui.value.copy(
            query = item.question,
            aiInput = item.question,
            aiAnswer = item.answer,
            searchedQuery = item.question,
            showHistory = false
        )
    }

    fun clearQaHistory() = viewModelScope.launch { aiHistoryRepo.clear() }

    private fun loadPresets() {
        val custom = mutableListOf<AiPreset>()
        val raw = presetSp.getString("custom", "") ?: ""
        if (raw.isNotBlank()) {
            raw.split("\u0001").forEachIndexed { i, item ->
                val parts = item.split("\u0002")
                if (parts.size >= 2) {
                    custom.add(AiPreset(1000L + i, parts[0], parts[1]))
                }
            }
        }
        _ui.value = _ui.value.copy(presets = AiRepository.builtinPresets() + custom)
    }

    fun setTab(t: NetTab) {
        _ui.value = _ui.value.copy(tab = t)
        // 切到汇率页时自动拉一次（1 分钟缓存内不重复请求），不用用户先点按钮
        if (t == NetTab.RATE) ensureRatesLoaded()
    }
    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }

    // ---------- 搜索 ----------

    fun setQuery(q: String) { _ui.value = _ui.value.copy(query = q) }

    fun search() = viewModelScope.launch {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) {
            _ui.value = _ui.value.copy(message = "请输入搜索内容")
            return@launch
        }
        _ui.value = _ui.value.copy(searching = true, results = emptyList(), searchErrors = emptyList())
        runCatching { searchRepo.search(q) }.fold(
            onSuccess = { r ->
                _ui.value = _ui.value.copy(
                    searching = false,
                    results = r.results,
                    searchErrors = r.errors,
                    searchedQuery = q,
                    message = if (r.results.isEmpty() && r.errors.isEmpty()) "没有找到结果" else null
                )
            },
            onFailure = { e ->
                _ui.value = _ui.value.copy(
                    searching = false,
                    message = "搜索失败：" + (e.message ?: "网络错误")
                )
            }
        )
    }

    // ---------- 快捷 AI 搜索（搜索 + AI 合并） ----------

    fun toggleWebSearch() { _ui.value = _ui.value.copy(webSearch = !_ui.value.webSearch) }
    fun toggleDeepThink() { _ui.value = _ui.value.copy(deepThink = !_ui.value.deepThink) }

    /**
     * 一键：先（可选）联网检索，再把问题与检索资料一起交给 AI。
     * - 打开「联网搜索」时才调用搜索引擎；
     * - 打开「深度思考」时用更长的系统提示与低温度。
     * 保留汇率快捷识别：输入「美元 人民币」会直接切到汇率页。
     */
    /**
     * 第一步：只搜索，不问 AI。结果留在页面上，用户看过之后再决定要不要让 AI 来答。
     * （用户要求"先搜索，然后进入 AI"——搜出来的东西和 AI 回答要能对上。）
     */
    fun searchNow() = viewModelScope.launch {
        val s0 = _ui.value
        val q = s0.query.trim()
        if (q.isEmpty()) {
            _ui.value = s0.copy(message = "请输入要搜索的内容")
            return@launch
        }
        if (tryRateFromQuery(q)) return@launch
        _ui.value = s0.copy(
            searching = true, results = emptyList(), searchErrors = emptyList(),
            aiAnswer = "", message = null
        )
        runCatching { searchRepo.search(q) }.fold(
            onSuccess = { r ->
                _ui.value = _ui.value.copy(
                    searching = false, results = r.results, searchErrors = r.errors, searchedQuery = q
                )
            },
            onFailure = { e ->
                _ui.value = _ui.value.copy(
                    searching = false,
                    searchErrors = listOf("搜索失败：" + (e.message ?: "网络错误"))
                )
            }
        )
    }

    /**
     * 第二步（或不开联网搜索时直接用）：问 AI。
     * 如果当前输入与上一次搜索的关键词一致，会把那批搜索结果作为参考资料一起发出去；
     * 否则就是纯提问（模型自己的知识 + 附件）。
     */
    fun askWithResults() = viewModelScope.launch {
        val s0 = _ui.value
        val q = s0.query.trim()
        if (q.isEmpty() && s0.attachments.isEmpty()) {
            _ui.value = s0.copy(message = "请输入要问的问题，或先添加附件")
            return@launch
        }
        val question = q.ifBlank { "请阅读这些附件，告诉我重点内容。" }
        if (tryRateFromQuery(q)) return@launch
        if (!config.isAiReady()) {
            _ui.value = s0.copy(message = "AI 还没配置：到「设置」填 BaseURL / Key / 模型名")
            return@launch
        }

        // 只引用"和当前问题同一次搜索"的结果，避免拿旧结果答新问题
        val results: List<SearchResult> =
            if (s0.searchedQuery.isNotBlank() && s0.searchedQuery == question) s0.results else emptyList()

        _ui.value = s0.copy(aiBusy = true, aiAnswer = "", message = null)
        val prompt = buildQuickPrompt(question, results)
        val atts = withContext(Dispatchers.IO) { resolveAttachments(s0.attachments) }
        runCatching {
            aiRepo.chat(
                userText = prompt,
                history = s0.aiHistory.takeLast(8),
                presetSystemPrompt = currentPreset()?.systemPrompt ?: "",
                deepThink = s0.deepThink,
                attachments = atts
            )
        }.fold(
            onSuccess = { answer ->
                _ui.value = _ui.value.copy(
                    aiBusy = false,
                    aiAnswer = answer,
                    aiHistory = _ui.value.aiHistory + ChatMessage("user", question) +
                        ChatMessage("assistant", answer)
                )
                viewModelScope.launch { aiHistoryRepo.add(question, answer) }
            },
            onFailure = { e ->
                _ui.value = _ui.value.copy(aiBusy = false, message = e.message ?: "AI 请求失败")
            }
        )
    }

    /** 兼容旧调用：开联网搜索时等于"搜索+AI 一起做" */
    fun quickAsk() = viewModelScope.launch {
        if (_ui.value.webSearch) {
            searchNow()
            val q = _ui.value.query.trim()
            if (_ui.value.results.isNotEmpty()) askWithResults() else if (!config.isAiReady()) return@launch
            else if (q.isNotBlank()) askWithResults()
        } else {
            askWithResults()
        }
    }

    fun clearAiHistory() {
        _ui.value = _ui.value.copy(aiHistory = emptyList(), aiAnswer = "")
    }

    /** 测试 AI 接口：真的发一次最小请求，结果直接回显在设置页 */
    fun testAi() = viewModelScope.launch {
        if (_ui.value.aiTesting) return@launch
        _ui.value = _ui.value.copy(aiTesting = true, aiTestResult = "", message = null)
        val r = aiRepo.test()
        _ui.value = _ui.value.copy(aiTesting = false, aiTestResult = r)
    }

    /**
     * 保存一条自定义预设。**任何分支都会给用户反馈**（以前名称/要求留空会静默返回，
     * 用户看到的就是"点了没反应"）。
     * @return 是否保存成功（UI 据此清空输入框）
     */
    fun addPreset(name: String, prompt: String): Boolean {
        val n = name.trim()
        val p = prompt.trim()
        if (n.isBlank()) {
            _ui.value = _ui.value.copy(message = "请先填「预设名称」")
            return false
        }
        if (p.isBlank()) {
            _ui.value = _ui.value.copy(message = "请先填「预设要求（系统提示词）」")
            return false
        }
        if (_ui.value.presets.any { it.name == n }) {
            _ui.value = _ui.value.copy(message = "已存在同名预设「" + n + "」，换个名字或先删掉旧的")
            return false
        }
        val old = presetSp.getString("custom", "") ?: ""
        val item = n + "\u0002" + p
        presetSp.edit().putString("custom", if (old.isBlank()) item else old + "\u0001" + item).apply()
        loadPresets()
        // 新加的立即选中，用户能马上在提问页看到效果
        val newId = _ui.value.presets.filterNot { it.builtin }.lastOrNull()?.id ?: 1L
        _ui.value = _ui.value.copy(
            selectedPresetId = newId,
            message = "已保存预设「" + n + "」，提问页顶部已选中"
        )
        return true
    }

    /** 删除一条自定义预设（内置预设不允许删） */
    fun removePreset(id: Long) {
        val target = _ui.value.presets.firstOrNull { it.id == id } ?: return
        if (target.builtin) {
            _ui.value = _ui.value.copy(message = "内置预设不能删除")
            return
        }
        val remain = _ui.value.presets.filter { !it.builtin && it.id != id }
        val raw = remain.joinToString("\u0001") { it.name + "\u0002" + it.systemPrompt }
        presetSp.edit().putString("custom", raw).apply()
        loadPresets()
        val stillThere = _ui.value.presets.any { it.id == _ui.value.selectedPresetId }
        _ui.value = _ui.value.copy(
            selectedPresetId = if (stillThere) _ui.value.selectedPresetId else NO_PRESET,
            message = "已删除预设「" + target.name + "」"
        )
    }

    // ---------- AI 输入 / 预设 ----------

    fun setAiInput(v: String) { _ui.value = _ui.value.copy(aiInput = v) }

    /** 点同一个预设 = 取消选择；点「不用预设」也回到 NO_PRESET */
    fun selectPreset(id: Long) {
        val cur = _ui.value.selectedPresetId
        _ui.value = _ui.value.copy(selectedPresetId = if (id == cur) NO_PRESET else id)
    }

    /** 清掉预设选择（提问时用默认助手提示词） */
    fun clearPreset() {
        _ui.value = _ui.value.copy(selectedPresetId = NO_PRESET)
    }

    fun currentPreset(): AiPreset? =
        _ui.value.presets.firstOrNull { it.id == _ui.value.selectedPresetId }

    /** 把联网检索结果整理成交给 AI 的参考资料（要求它标注来源编号） */
    /** 把联网检索结果整理成交给 AI 的参考资料（要求它标注来源编号） */
    internal fun buildQuickPrompt(q: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return q
        val sb = StringBuilder()
        sb.append("请根据下面这些联网检索结果回答用户的问题；")
        sb.append("回答末尾用 [1][2] 这样的编号标注你引用的来源，不要编造资料里没有的内容。")
        sb.append("\n\n【用户问题】\n").append(q)
        sb.append("\n\n【检索结果】\n")
        results.take(6).forEachIndexed { i, r ->
            sb.append('[').append(i + 1).append("] ").append(r.title).append('\n')
            if (r.snippet.isNotBlank()) sb.append(r.snippet).append('\n')
            if (r.url.isNotBlank()) sb.append(r.url).append('\n')
            if (r.source.isNotBlank()) sb.append("（来源：").append(r.source).append("）\n")
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---------- 附件 ----------

    fun addAttachments(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        val ctx = getApplication<Application>()
        val added = withContext(Dispatchers.IO) {
            uris.mapNotNull { u ->
                runCatching {
                    val cr = ctx.contentResolver
                    // file:// 之类 Uri 查不到 DISPLAY_NAME/MIME，就用路径里的文件名兜底
                    var name = u.lastPathSegment?.substringAfterLast('/')?.ifBlank { "附件" } ?: "附件"
                    var size = 0L
                    var mime = cr.getType(u).orEmpty()
                    cr.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (ni >= 0) c.getString(ni)?.takeIf { it.isNotBlank() }?.let { name = it }
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (si >= 0) size = c.getLong(si)
                        }
                    }
                    if (mime.isBlank()) {
                        mime = java.net.URLConnection.guessContentTypeFromName(name).orEmpty()
                    }
                    if (size > MAX_ATTACH_BYTES) throw IllegalStateException("too big")
                    PendingAttachment(u, name, mime, size)
                }.getOrNull()
            }
        }
        if (added.isEmpty()) {
            _ui.value = _ui.value.copy(
                message = "附件读取失败（可能没给读取权限，或超过 " + (MAX_ATTACH_BYTES / 1024 / 1024) + "MB）"
            )
        } else {
            _ui.value = _ui.value.copy(attachments = _ui.value.attachments + added, message = null)
        }
    }

    fun removeAttachment(uri: Uri) {
        _ui.value = _ui.value.copy(attachments = _ui.value.attachments.filterNot { it.uri == uri })
    }

    fun clearAttachments() {
        _ui.value = _ui.value.copy(attachments = emptyList())
    }

    /**
     * 把待发送附件解析成可发出去的形式（全部流式读取，大文件也不 OOM）：
     * - 图片：先读边界 + inSampleSize 下采样，再解码 → 长边 <=1280 → base64（OpenAI 视觉消息）
     * - 文本类：只读前 [MAX_ATTACH_CHARS] 个字
     * - PDF：拷到缓存临时文件交给 PDFBox 抽文字（前 [MAX_PDF_PAGES] 页）
     * - Word/Excel/PPT：zip 流里只取需要的 xml
     * - 其它：只把文件名与大小告诉模型
     */
    internal fun resolveAttachments(list: List<PendingAttachment>): List<AiAttachment> {
        return list.map { a ->
            val ext = a.name.substringAfterLast('.', "").lowercase()
            runCatching {
                when {
                    a.mime.startsWith("image/") || ext in IMAGE_EXT -> imageAttachment(a)
                    ext == "pdf" -> pdfAttachment(a)
                    ext == "docx" -> zipTextsAttachment(a, listOf("word/document.xml"))
                    ext == "pptx" -> zipTextsAttachment(a, null)
                    ext == "xlsx" -> zipTextsAttachment(a, listOf("xl/sharedStrings.xml"))
                    a.mime.startsWith("text/") || ext in TEXT_EXT -> textAttachment(a)
                    else -> AiAttachment(
                        a.name, a.mime,
                        note = "该类型暂不解析内容，大小 " + humanSize(a.size)
                    )
                }
            }.getOrElse { AiAttachment(a.name, a.mime, note = "读取失败：" + (it.message ?: "未知错误")) }
        }
    }

    private fun imageAttachment(a: PendingAttachment): AiAttachment {
        val ctx = getApplication<Application>()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(a.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return AiAttachment(a.name, a.mime, note = "图片解码失败")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > VISION_MAX_SIDE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = ctx.contentResolver.openInputStream(a.uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return AiAttachment(a.name, a.mime, note = "图片解码失败")
        val scaled = downscaleForVision(bmp, VISION_MAX_SIDE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return AiAttachment(
            a.name, "image/jpeg",
            base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        )
    }

    private fun textAttachment(a: PendingAttachment): AiAttachment {
        val ctx = getApplication<Application>()
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(a.uri)?.use { input ->
            input.reader(Charsets.UTF_8).use { r ->
                val buf = CharArray(8192)
                while (sb.length < MAX_ATTACH_CHARS) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, minOf(n, MAX_ATTACH_CHARS - sb.length))
                }
            }
        }
        if (sb.isEmpty()) return AiAttachment(a.name, a.mime, note = "文件为空或读取失败")
        return AiAttachment(a.name, a.mime, text = sb.toString())
    }

    /** PDF：拷到缓存临时文件（流式）→ PDFBox 抽文字 */
    private fun pdfAttachment(a: PendingAttachment): AiAttachment {
        val ctx = getApplication<Application>()
        initPdfBox(ctx)
        val tmp = File(ctx.cacheDir, "ai_att_" + System.currentTimeMillis() + ".pdf")
        try {
            ctx.contentResolver.openInputStream(a.uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            } ?: return AiAttachment(a.name, a.mime, note = "读取失败")
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(tmp).use { doc ->
                if (doc.isEncrypted) return AiAttachment(a.name, a.mime, note = "PDF 已加密，无法解析")
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = minOf(doc.numberOfPages, MAX_PDF_PAGES)
                var text = stripper.getText(doc).trim()
                if (text.isBlank()) {
                    return AiAttachment(
                        a.name, a.mime,
                        note = doc.numberOfPages.toString() + " 页，没抽出文字（可能是扫描件/纯图片 PDF）"
                    )
                }
                val more = if (doc.numberOfPages > MAX_PDF_PAGES)
                    "\n…（共 " + doc.numberOfPages + " 页，只解析了前 " + MAX_PDF_PAGES + " 页）" else ""
                if (text.length > MAX_ATTACH_CHARS) text = text.take(MAX_ATTACH_CHARS) + "\n…（过长已截断）"
                return AiAttachment(a.name, a.mime, text = text + more)
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** docx / xlsx / pptx：都是 zip+xml；[entries] 为空时收集所有 ppt/slides/slideN.xml */
    private fun zipTextsAttachment(a: PendingAttachment, entries: List<String>?): AiAttachment {
        val ctx = getApplication<Application>()
        val parts = ArrayList<String>()
        ctx.contentResolver.openInputStream(a.uri)?.use { input ->
            java.util.zip.ZipInputStream(input).use { zin ->
                var e = zin.nextEntry
                while (e != null && parts.size < MAX_SLIDES) {
                    val hit = if (entries != null) e.name in entries
                    else e.name.startsWith("ppt/slides/slide") && e.name.endsWith(".xml")
                    if (hit) {
                        val sb = StringBuilder()
                        val chunk = ByteArray(16 * 1024)
                        var total = 0
                        while (total < MAX_ZIP_ENTRY_BYTES) {
                            val n = zin.read(chunk)
                            if (n <= 0) break
                            sb.append(String(chunk, 0, minOf(n, MAX_ZIP_ENTRY_BYTES - total), Charsets.UTF_8))
                            total += n
                        }
                        val txt = sb.toString().replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("[ \\t\\r\\n]+"), " ").trim()
                        if (txt.isNotBlank()) parts.add(txt)
                    }
                    e = zin.nextEntry
                }
            }
        } ?: return AiAttachment(a.name, a.mime, note = "读取失败")
        if (parts.isEmpty()) return AiAttachment(a.name, a.mime, note = "没抽出文字")
        var text = parts.joinToString("\n")
        if (text.length > MAX_ATTACH_CHARS) text = text.take(MAX_ATTACH_CHARS) + "\n…（过长已截断）"
        return AiAttachment(a.name, a.mime, text = text)
    }

    private fun initPdfBox(ctx: android.content.Context) {
        if (pdfBoxReady) return
        synchronized(this) {
            if (!pdfBoxReady) {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
                pdfBoxReady = true
            }
        }
    }

    private fun downscaleForVision(bmp: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(bmp.width, bmp.height)
        if (m <= maxSide) return bmp
        val r = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(
            bmp, maxOf(1, (bmp.width * r).toInt()), maxOf(1, (bmp.height * r).toInt()), true
        )
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "未知大小"
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    // ---------- 汇率 ----------

    fun availableCurrencies(): List<Pair<String, String>> = rateRepo.availableCurrencies()

    fun setRateFrom(code: String) { _ui.value = _ui.value.copy(rateFrom = code.uppercase(), rateResult = "") }
    fun setRateTo(code: String) { _ui.value = _ui.value.copy(rateTo = code.uppercase(), rateResult = "") }
    fun setRateAmount(v: String) { _ui.value = _ui.value.copy(rateAmount = v) }

    /** 切到汇率页/点刷新时用：没有表或已过期就拉一次；1 分钟内的新数据不重复请求 */
    fun ensureRatesLoaded() {
        val s = _ui.value
        if (s.rateLoading) return
        val fresh = s.rateTable != null &&
            System.currentTimeMillis() - (s.rateTable?.updatedAt ?: 0L) < 60_000L
        if (!fresh) loadRates(s.rateBase)
    }

    /** 强制刷新（用户点「刷新」） */
    fun refreshRates() = loadRates(_ui.value.rateBase, force = true)

    /** 拉一次汇率表（1 分钟内存缓存；force=true 时强制重新请求），然后立刻换算一次 */
    fun loadRates(base: String = _ui.value.rateBase, force: Boolean = false) = viewModelScope.launch {
        _ui.value = _ui.value.copy(rateLoading = true, rateBase = base.uppercase())
        runCatching { rateRepo.rates(base, force) }.fold(
            onSuccess = { table ->
                _ui.value = _ui.value.copy(rateLoading = false, rateTable = table)
                computeRate()
            },
            onFailure = { e ->
                _ui.value = _ui.value.copy(
                    rateLoading = false,
                    rateResult = "",
                    message = "汇率获取失败：" + (e.message ?: "网络错误")
                )
            }
        )
    }

    /** 用当前汇率表算一次结果 */
    fun computeRate() {
        val s = _ui.value
        val table = s.rateTable ?: return
        val amount = s.rateAmount.trim().toDoubleOrNull()
        if (amount == null) {
            _ui.value = s.copy(rateResult = "")
            return
        }
        val v = rateRepo.convert(table, s.rateFrom, s.rateTo, amount)
        _ui.value = s.copy(
            rateResult = if (v == null) "" else "%.4f".format(v).trimEnd('0').trimEnd('.') + " " + s.rateTo
        )
    }

    /**
     * 提问框里输入「美元 人民币」「100 USD to CNY」这类内容时，直接跳到汇率页换算，不走 AI。
     * @return true = 已识别为汇率查询
     */
    private fun tryRateFromQuery(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val pair = runCatching { rateRepo.parseQuery(t) }.getOrNull() ?: return false
        val amount = runCatching { rateRepo.parseAmount(t) }.getOrDefault(1.0)
        _ui.value = _ui.value.copy(
            tab = NetTab.RATE,
            rateFrom = pair.first.uppercase(),
            rateTo = pair.second.uppercase(),
            rateAmount = if (amount == amount.toLong().toDouble()) amount.toLong().toString()
            else amount.toString(),
            rateResult = "",
            message = null
        )
        loadRates(_ui.value.rateBase, force = true)
        return true
    }

    // ---------- 设置 ----------

    fun config(): NetConfig = config

    fun refreshAfterSettingsChange() {
        _ui.value = _ui.value.copy(message = "设置已保存")
    }

    /** 供设置页读取：当前哪些引擎可用 */
    fun usableEngines(): List<SearchEngine> = SearchEngine.entries.filter { config.isUsable(it) }
}
