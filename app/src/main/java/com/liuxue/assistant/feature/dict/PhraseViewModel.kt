package com.liuxue.assistant.feature.dict

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.dict.DictRepository
import com.liuxue.assistant.data.dict.Phrase
import com.liuxue.assistant.data.dict.PhraseCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PhraseState(
    /** -1 = 全部 */
    val categoryId: Long = -1L,
    val categories: List<PhraseCategory> = emptyList(),
    val phrases: List<Phrase> = emptyList()
)

/**
 * 自定义常用短语集。
 * 可自定义分类，支持收藏、搜索、导入导出。
 */
class PhraseViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: DictRepository = AppContainer.dictRepository(app)

    private val _categoryId = MutableStateFlow(-1L)
    val categoryId: StateFlow<Long> = _categoryId.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<PhraseState> =
        combine(
            repo.observePhraseCategories(),
            repo.observePhrases(),
            _categoryId,
            _query
        ) { cats, phrases, cat, q ->
            val filtered = phrases
                .filter { cat < 0 || it.categoryId == cat }
                .filter {
                    q.isBlank() || it.textRu.contains(q, true) ||
                        it.textZh.contains(q, true) || it.note.contains(q, true)
                }
            PhraseState(categoryId = cat, categories = cats, phrases = filtered)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhraseState())

    fun selectCategory(id: Long) { _categoryId.value = id }
    fun setQuery(q: String) { _query.value = q }

    fun add(textRu: String, textZh: String, note: String, categoryId: Long) =
        viewModelScope.launch {
            if (textRu.isBlank() && textZh.isBlank()) {
                _message.value = "内容不能为空"
                return@launch
            }
            repo.addPhrase(textRu.trim(), textZh.trim(), note.trim(), categoryId)
            _message.value = "已添加"
        }

    fun toggleStar(p: Phrase) = viewModelScope.launch { repo.togglePhraseStar(p.id, !p.starred) }

    fun delete(p: Phrase) = viewModelScope.launch { repo.deletePhrase(p.id); _message.value = "已删除" }

    fun addCategory(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.addPhraseCategory(name.trim())
    }

    fun deleteCategory(id: Long) = viewModelScope.launch {
        repo.deletePhraseCategory(id)
        if (_categoryId.value == id) _categoryId.value = -1L
    }

    fun clearMessage() { _message.value = null }

    /** 导出为可分享的纯文本 */
    fun exportText(): String {
        val s = state.value
        val sb = StringBuilder("留学助手-乐 · 常用短语导出\n")
        s.categories.forEach { c ->
            val items = s.phrases.filter { it.categoryId == c.id }
            if (items.isNotEmpty()) {
                sb.append("\n【").append(c.name).append("】\n")
                items.forEach { sb.append(it.textRu).append("  |  ").append(it.textZh).append("\n") }
            }
        }
        val loose = s.phrases.filter { p -> s.categories.none { it.id == p.categoryId } }
        if (loose.isNotEmpty()) {
            sb.append("\n【未分类】\n")
            loose.forEach { sb.append(it.textRu).append("  |  ").append(it.textZh).append("\n") }
        }
        return sb.toString()
    }
}
