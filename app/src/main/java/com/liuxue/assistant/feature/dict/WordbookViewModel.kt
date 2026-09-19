package com.liuxue.assistant.feature.dict

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.dict.DictEntry
import com.liuxue.assistant.data.dict.DictRepository
import com.liuxue.assistant.data.dict.WordbookCategory
import com.liuxue.assistant.data.dict.WordbookItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 生词本一行：用户记录 + 词条详情 */
data class WordbookRow(val item: WordbookItem, val entry: DictEntry?)

data class WordbookState(
    /** -1 = 全部分类 */
    val categoryId: Long = -1L,
    val categories: List<WordbookCategory> = emptyList(),
    val rows: List<WordbookRow> = emptyList()
)

class WordbookViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: DictRepository = AppContainer.dictRepository(app)

    private val _categoryId = MutableStateFlow(-1L)
    val categoryId: StateFlow<Long> = _categoryId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val rowsFlow = MutableStateFlow<List<WordbookRow>>(emptyList())

    val state: StateFlow<WordbookState> =
        combine(repo.observeWordbookCategories(), rowsFlow, _categoryId) { cats, _, cat ->
            WordbookState(categoryId = cat, categories = cats, rows = rowsFlow.value)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WordbookState())

    init {
        // 生词本条目 -> 逐条补齐词条详情（需要 suspend 查询，所以单独收集）
        viewModelScope.launch {
            combine(repo.observeWordbook(), _categoryId) { items, cat ->
                if (cat < 0) items else items.filter { it.categoryId == cat }
            }.mapLatest { items ->
                items.map { WordbookRow(it, repo.entry(it.entryId)) }
            }.collect { rowsFlow.value = it }
        }
    }

    val totalCount: StateFlow<Int> = repo.observeWordbookCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun selectCategory(id: Long) { _categoryId.value = id }

    fun setMastery(item: WordbookItem, mastery: Int) = viewModelScope.launch {
        repo.setMastery(item.entryId, mastery)
        _message.value = "已标记为「" + masteryLabel(mastery) + "」"
    }

    fun remove(item: WordbookItem) = viewModelScope.launch {
        repo.removeFromWordbook(item.entryId)
        _message.value = "已移出生词本"
    }

    fun addCategory(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.addWordbookCategory(name.trim())
    }

    fun deleteCategory(id: Long) = viewModelScope.launch {
        repo.deleteWordbookCategory(id)
        if (_categoryId.value == id) _categoryId.value = -1L
    }

    fun clearMessage() { _message.value = null }

    companion object {
        fun masteryLabel(m: Int) = when (m) {
            3 -> "已掌握"; 2 -> "熟悉"; 1 -> "模糊"; else -> "新词"
        }
    }
}
