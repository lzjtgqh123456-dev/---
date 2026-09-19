package com.liuxue.assistant.feature.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.db.VaultFile
import com.liuxue.assistant.data.repo.VaultRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class FileListViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: VaultRepository = AppContainer.vaultRepository(app)

    /** null = 全部分类 */
    private val _category = MutableStateFlow<String?>(null)
    val category: StateFlow<String?> = _category.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val items: StateFlow<List<VaultFile>> =
        combineQueryAndCategory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vaultSize = MutableStateFlow(0L)

    private fun combineQueryAndCategory() =
        kotlinx.coroutines.flow.combine(_query.debounce(180), _category) { q, c -> q.trim() to c }
            .flatMapLatest { (q, c) ->
                when {
                    q.isNotEmpty() -> repo.search(q)
                    c != null -> repo.observeByCategory(c)
                    else -> repo.observeAll()
                }
            }

    fun selectCategory(key: String?) { _category.value = key }

    fun setQuery(q: String) { _query.value = q }

    fun refreshSize() = viewModelScope.launch { vaultSize.value = repo.vaultSize() }

    fun togglePin(item: VaultFile) = viewModelScope.launch {
        repo.setPinned(item.id, !item.pinned)
    }

    fun delete(item: VaultFile) = viewModelScope.launch {
        repo.delete(item)
        refreshSize()
    }
}
