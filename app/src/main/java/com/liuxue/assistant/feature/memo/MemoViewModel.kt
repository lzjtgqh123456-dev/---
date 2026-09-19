package com.liuxue.assistant.feature.memo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.data.db.Memo
import com.liuxue.assistant.data.db.MemoAttachment
import com.liuxue.assistant.data.repo.MemoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 待保存的附件（还没加密入库） */
data class MemoFile(val uri: Uri, val name: String, val mime: String)

/** 列表项：记录 + 解密后的正文 + 附件 */
data class MemoRow(
    val memo: Memo,
    val body: String,
    val attachments: List<MemoAttachment> = emptyList()
)

class MemoViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MemoRepository(app)
    val query = MutableStateFlow("")

    val rows: StateFlow<List<MemoRow>> = combine(repo.observeAll(), query) { list, q ->
        list.map { m ->
            MemoRow(m, repo.decryptBody(m.bodyCipher), repo.attachments(m.id))
        }.filter { r ->
            q.isBlank() || r.memo.title.contains(q, ignoreCase = true) ||
                r.body.contains(q, ignoreCase = true)
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(s: String) { query.value = s }

    /** 保存（新建或编辑）：正文加密，附件加密落盘 */
    fun save(
        existing: Memo?,
        title: String,
        body: String,
        remindAt: Long?,
        files: List<MemoFile> = emptyList(),
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            val cipher = repo.encryptBody(body)
            val base = existing ?: Memo(title = title)
            val id = repo.save(
                base.copy(
                    title = title.trim(),
                    bodyCipher = cipher,
                    remindAt = remindAt,
                    notifiedFor = null
                )
            )
            files.forEach { f -> runCatching { repo.addAttachment(id, f.uri, f.name, f.mime) } }
            onDone()
        }
    }

    fun togglePin(m: Memo) = viewModelScope.launch { repo.save(m.copy(pinned = !m.pinned)) }

    fun delete(m: Memo) = viewModelScope.launch { repo.delete(m) }

    // ---------- 附件 ----------

    fun deleteAttachment(a: MemoAttachment) = viewModelScope.launch { repo.deleteAttachment(a) }

    fun loadAttachmentBytes(a: MemoAttachment, cb: (ByteArray?) -> Unit) =
        viewModelScope.launch { cb(repo.attachmentBytes(a)) }

    fun shareAttachment(a: MemoAttachment, cb: (java.io.File?) -> Unit) =
        viewModelScope.launch { cb(repo.materializeAttachmentForShare(a)) }
}
