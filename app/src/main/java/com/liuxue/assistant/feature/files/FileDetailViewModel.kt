package com.liuxue.assistant.feature.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.db.VaultAttachment
import com.liuxue.assistant.data.db.VaultFile
import com.liuxue.assistant.data.repo.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class DetailState(
    val item: VaultFile? = null,
    val cover: ByteArray? = null,
    val attachments: List<VaultAttachment> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null
)

class FileDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: VaultRepository = AppContainer.vaultRepository(app)
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val item = repo.find(id)
        _state.value = DetailState(
            item = item,
            cover = item?.let { repo.coverBytes(it) },
            attachments = repo.attachments(id),
            loading = false
        )
    }

    fun reloadAttachments(id: Long) = viewModelScope.launch {
        _state.value = _state.value.copy(attachments = repo.attachments(id))
    }

    fun addAttachment(id: Long, uri: Uri, name: String, mime: String) = viewModelScope.launch {
        runCatching { repo.addAttachment(id, uri, name, mime) }
            .onSuccess { reloadAttachments(id) }
            .onFailure { _state.value = _state.value.copy(message = "导入失败：" + (it.message ?: "未知错误")) }
    }

    fun deleteAttachment(att: VaultAttachment) = viewModelScope.launch {
        repo.deleteAttachment(att)
        reloadAttachments(att.fileId)
    }

    fun togglePin() = viewModelScope.launch {
        _state.value.item?.let {
            repo.setPinned(it.id, !it.pinned)
            load(it.id)
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        _state.value.item?.let { repo.delete(it) }
        onDone()
    }

    /** 解密指定加密文件为可分享的明文文件 */
    suspend fun prepareForShare(path: String, name: String): File? =
        repo.materializeForShare(path, name)

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    suspend fun attachmentBytes(att: VaultAttachment): ByteArray? = repo.attachmentBytes(att)
}
