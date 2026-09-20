package com.liuxue.assistant.feature.dict

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.dict.DictAssetDb
import com.liuxue.assistant.data.dict.DictEntry
import com.liuxue.assistant.data.dict.DictForm
import com.liuxue.assistant.data.dict.DictManager
import com.liuxue.assistant.data.dict.DictRepository
import com.liuxue.assistant.data.dict.LookupHit
import com.liuxue.assistant.data.dict.WordbookItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** 查词结果卡片的数据 */
data class EntryCard(
    val entry: DictEntry,
    val matchedTags: List<String>,
    val forms: List<DictForm> = emptyList(),
    val inWordbook: Boolean = false
)

enum class SearchMode { FORWARD, REVERSE }

data class DictState(
    val query: String = "",
    val mode: SearchMode = SearchMode.FORWARD,
    val pack: DictAssetDb.Pack = DictAssetDb.RU_ZH,
    val suggestions: List<LookupHit> = emptyList(),
    val results: List<EntryCard> = emptyList(),
    val searching: Boolean = false,
    val dictReady: Boolean = false,
    val dictStats: Pair<Int, Int> = 0 to 0,
    val favorites: Set<Long> = emptySet(),
    val message: String? = null,
    // ---------- 词典包管理 ----------
    val installedFiles: Set<String> = emptySet(),
    val packSizes: Map<String, Long> = emptyMap(),
    val downloadingKey: String? = null,
    val downloadProgress: Float = 0f,
    val downloadMirror: Int = 0,
    val managerMessage: String? = null
)

@OptIn(FlowPreview::class)
class DictViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: DictRepository = AppContainer.dictRepository(app)

    private val _state = MutableStateFlow(DictState())
    val state: StateFlow<DictState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    val wordbook = repo.observeWordbook()
    val wordbookCount = repo.observeWordbookCount()
    val wordbookCategories = repo.observeWordbookCategories()

    init {
        loadStats()
        refreshPacks()
        // 输入即反馈：俄语用前缀联想选词；中文直接自动反查（中文没有"前缀"概念，
        // 且用户报过"切到汉俄输中文没反应"——那时后台跑的是俄语前缀联想，必然为空）
        viewModelScope.launch {
            queryFlow.debounce(150).filter { it.isNotBlank() }.collect { q ->
                val text = q.trim()
                if (text.isEmpty()) return@collect
                // 这里只处理俄语前缀联想；中文反查在 setQuery 里即时完成
                if (_state.value.mode == SearchMode.FORWARD) {
                    val s = runCatching { repo.suggest(text) }.getOrDefault(emptyList())
                    _state.value = _state.value.copy(suggestions = s.take(12))
                }
            }
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)

        // 中文反查：本地 SQLite 查询很快，逐字即时出结果，不做防抖、不用点搜索
        if (_state.value.mode == SearchMode.REVERSE) {
            val text = q.trim()
            if (text.isEmpty()) {
                _state.value = _state.value.copy(results = emptyList(), suggestions = emptyList())
                return
            }
            viewModelScope.launch {
                val hits = runCatching { repo.searchChinese(text) }.getOrDefault(emptyList())
                // 查询期间用户可能又改了输入，丢弃过期结果
                if (text != _state.value.query.trim()) return@launch
                if (hits.isEmpty()) {
                    _state.value = _state.value.copy(results = emptyList(), suggestions = emptyList())
                } else {
                    buildCards(hits)
                }
            }
            return
        }

        queryFlow.value = q
        if (q.isBlank()) {
            _state.value = _state.value.copy(suggestions = emptyList(), results = emptyList())
        }
    }

    /** 切换词典入口（俄汉 / 俄英 / 英汉），并刷新词条统计与当前查询 */
    fun setPack(p: DictAssetDb.Pack) = viewModelScope.launch {
        if (p.id == _state.value.pack.id) return@launch
        // 注意：这里**不要**先把 dictReady 置 false —— 否则切换词典包的一瞬间，
        // 「未安装 + 下载词典」卡片会闪一下（用户反馈"一闪而过下载的提示"）。
        // 真实状态由下面的 loadStats() 刷新。
        _state.value = _state.value.copy(
            pack = p, results = emptyList(), suggestions = emptyList(),
            message = null, dictStats = 0 to 0
        )
        // 内置包（英汉）若被删过，这里 open() 会自动从 APK 重新释放；无内置资源的包会失败，由 loadStats 提示未安装
        val opened = runCatching { repo.setPack(p) }.isSuccess
        loadStats()
        if (opened) setQuery(_state.value.query)
    }

    private fun loadStats() = viewModelScope.launch {
        // 首次访问会从 assets 释放词典，放到 IO 线程
        val p = _state.value.pack
        if (!DictAssetDb.isReady(getApplication(), p)) {
            // 不弹提示：页面上那张「未安装 + 下载 / 导入」卡片已经说明了，弹 snackbar 只会一闪而过
            _state.value = _state.value.copy(dictReady = false, dictStats = 0 to 0, message = null)
            return@launch
        }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { repo.setPack(p); repo.stats() }
        }
        result.exceptionOrNull()?.let { e ->
            android.util.Log.e("DictInit", "词典初始化失败", e)
        }
        val ready = result.getOrNull()
        val installed = DictAssetDb.isReady(getApplication(), p)
        _state.value = _state.value.copy(
            dictReady = ready != null,
            dictStats = ready ?: (0 to 0),
            message = when {
                ready != null -> null
                !installed -> null          // 同上：未安装只由卡片提示
                else -> result.exceptionOrNull()?.let { "词典初始化失败：" + (it.message ?: it.javaClass.simpleName) }
            }
        )
    }

    // ---------- 词典包管理 ----------

    private val manager = DictManager(app)

    fun refreshPacks() {
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(
            installedFiles = DictAssetDb.FILES.filter { DictAssetDb.isReady(ctx, it) }.map { it.key }.toSet(),
            packSizes = DictAssetDb.FILES.associate { it.key to DictAssetDb.sizeOf(ctx, it) }
        )
    }

    fun packUrl(f: DictAssetDb.PackFile): String = manager.url(f)
    fun packHasDefaultSource(f: DictAssetDb.PackFile): Boolean = manager.defaultUrls(f).isNotEmpty()
    fun expectedBytes(f: DictAssetDb.PackFile): Long =
        if (f.key == "ru") com.liuxue.assistant.data.dict.DictPackMeta.RU_EXPECTED_SIZE else 0L
    fun setPackUrl(f: DictAssetDb.PackFile, url: String) = manager.setUrl(f, url)
    fun clearManagerMessage() { _state.value = _state.value.copy(managerMessage = null) }

    fun deletePack(f: DictAssetDb.PackFile) = viewModelScope.launch {
        val freed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { manager.delete(f) }
        refreshPacks(); loadStats()
        _state.value = _state.value.copy(
            managerMessage = "已删除「" + f.label + "」" + (if (freed > 0) "，释放 " + human(freed) else "")
        )
    }

    fun importPack(f: DictAssetDb.PackFile, uri: android.net.Uri) = viewModelScope.launch {
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { manager.importFrom(f, uri) }
        }
        refreshPacks(); loadStats()
        _state.value = _state.value.copy(
            managerMessage = r.fold(
                { "导入成功：" + f.label + "（" + human(it) + "）" },
                { "导入失败：" + (it.message ?: "未知错误") }
            )
        )
    }

    fun exportPack(f: DictAssetDb.PackFile, uri: android.net.Uri) = viewModelScope.launch {
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { manager.exportTo(f, uri) }
        }
        _state.value = _state.value.copy(
            managerMessage = r.fold(
                { "已导出：" + f.label },
                { "导出失败：" + (it.message ?: "未知错误") }
            )
        )
    }

    fun downloadPack(f: DictAssetDb.PackFile) = viewModelScope.launch {
        val urls = manager.urls(f)
        if (urls.isEmpty()) {
            _state.value = _state.value.copy(managerMessage = "没有下载地址，请到「词典管理」填写")
            return@launch
        }
        _state.value = _state.value.copy(
            downloadingKey = f.key,
            downloadProgress = 0f,
            downloadMirror = 0,
            managerMessage = "开始下载（%" + "s）".format(
                urls.first().substringAfter("//").substringBefore("/")
            )
        )
        val r = runCatching {
            manager.download(f) { d, total, mirror ->
                if (total > 0) _state.value = _state.value.copy(
                    downloadProgress = d * 100f / total,
                    downloadMirror = mirror
                )
            }
        }
        refreshPacks()
        // 成功就立刻切到「已就绪」：否则刷新完成前页面还会再显示一次「未安装 / 下载词典」卡片
        if (r.isSuccess) _state.value = _state.value.copy(dictReady = true)
        loadStats()
        _state.value = _state.value.copy(
            downloadingKey = null, downloadProgress = 0f,
            managerMessage = r.fold(
                { "下载完成：" + f.label + "（" + human(it) + "）" },
                { "下载失败：" + (it.message ?: "未知错误") }
            )
        )
    }

    private fun human(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    fun setMode(m: SearchMode) {
        _state.value = _state.value.copy(
            mode = m, results = emptyList(), suggestions = emptyList(), message = null
        )
        // 切模式后立刻用当前输入按新模式查一次
        setQuery(_state.value.query)
    }

    /** 执行查询：俄文模式支持任意变形，中文模式按释义反查 */
    fun search(query: String = _state.value.query) = viewModelScope.launch {
        val q = query.trim()
        if (q.isEmpty()) return@launch
        _state.value = _state.value.copy(searching = true, suggestions = emptyList(), message = null)
        val hits = runCatching {
            if (_state.value.mode == SearchMode.REVERSE) repo.searchChinese(q) else repo.lookup(q)
        }.getOrElse {
            _state.value = _state.value.copy(searching = false, message = "查询失败：" + (it.message ?: ""))
            return@launch
        }

        if (hits.isEmpty()) {
            _state.value = _state.value.copy(
                searching = false, results = emptyList(), message = "未找到「$q」"
            )
            return@launch
        }
        buildCards(hits)
    }

    private suspend fun buildCards(hits: List<LookupHit>) {
        val favs = mutableSetOf<Long>()
        val cards = hits.map { h ->
            val entry = repo.entry(h.entryId)
            val inWb = repo.isInWordbook(h.entryId)
            if (inWb) favs.add(h.entryId)
            EntryCard(
                entry = entry ?: DictEntry(h.entryId, h.lemma, h.lemma, h.pos, h.ipa, h.glossZh, h.glossEn, "lemma"),
                matchedTags = h.matchedTag?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                forms = repo.forms(h.entryId),
                inWordbook = inWb
            )
        }
        _state.value = _state.value.copy(
            searching = false, results = cards, favorites = favs, message = null
        )
    }

    fun toggleWordbook(entryId: Long) = viewModelScope.launch {
        if (entryId in _state.value.favorites) {
            repo.removeFromWordbook(entryId)
            _state.value = _state.value.copy(
                favorites = _state.value.favorites - entryId,
                results = _state.value.results.map {
                    if (it.entry.id == entryId) it.copy(inWordbook = false) else it
                },
                message = "已移出生词本"
            )
        } else {
            repo.addToWordbook(entryId)
            _state.value = _state.value.copy(
                favorites = _state.value.favorites + entryId,
                results = _state.value.results.map {
                    if (it.entry.id == entryId) it.copy(inWordbook = true) else it
                },
                message = "已加入生词本"
            )
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
