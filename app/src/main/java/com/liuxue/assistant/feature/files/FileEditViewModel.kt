package com.liuxue.assistant.feature.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.db.VaultFile
import com.liuxue.assistant.data.repo.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** 待随证件一起保存的附件（还没加密入库） */
data class NewDocFile(val uri: Uri, val name: String, val mime: String)

/** 编辑页状态 */
data class EditState(
    val id: Long = 0L,
    val title: String = "",
    val category: String = "passport",
    val note: String = "",
    val issueDate: Long? = null,
    val expireDate: Long? = null,
    val remindDaysBefore: Int = 30,
    val coverBytes: ByteArray? = null,
    val existingCoverPath: String? = null,
    val loading: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null
)

class FileEditViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: VaultRepository = AppContainer.vaultRepository(app)

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        if (id <= 0L) return@launch
        _state.value = _state.value.copy(loading = true)
        val item = repo.find(id)
        if (item == null) {
            _state.value = _state.value.copy(loading = false, error = "记录不存在")
            return@launch
        }
        val bytes = repo.coverBytes(item)
        _state.value = EditState(
            id = item.id,
            title = item.title,
            category = item.category,
            note = item.note,
            issueDate = item.issueDate,
            expireDate = item.expireDate,
            remindDaysBefore = item.remindDaysBefore,
            coverBytes = bytes,
            existingCoverPath = item.coverPath,
            loading = false
        )
    }

    fun setTitle(v: String) = _state.update { it.copy(title = v) }
    fun setCategory(v: String) = _state.update { it.copy(category = v) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }
    fun setIssueDate(v: Long?) = _state.update { it.copy(issueDate = v) }
    fun setExpireDate(v: Long?) = _state.update { it.copy(expireDate = v) }
    fun setRemindDays(v: Int) = _state.update { it.copy(remindDaysBefore = v) }

    /** 收到图片字节（来自相册或拍照），压缩后暂存，保存时才写盘 */
    fun setCover(raw: ByteArray) = viewModelScope.launch {
        val compressed = withContext(Dispatchers.Default) { compress(raw) }
        _state.update { it.copy(coverBytes = compressed) }
    }

    fun clearCover() = _state.update { it.copy(coverBytes = null) }

    /** 读取拍照产生的临时文件 */
    fun setCoverFromFile(file: File) = viewModelScope.launch {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { file.readBytes() }.getOrNull()
        }
        if (bytes != null) {
            file.delete()
            setCover(bytes)
        } else {
            _state.update { it.copy(error = "读取照片失败") }
        }
    }

    private fun compress(raw: ByteArray, maxSide: Int = 1600, quality: Int = 82): ByteArray {
        val decoded = runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()
            ?: return raw
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else decoded
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun save(files: List<NewDocFile> = emptyList(), onDone: () -> Unit) = viewModelScope.launch {
        val s = _state.value
        if (s.title.isBlank()) {
            _state.value = s.copy(error = "请填写名称")
            return@launch
        }
        if (s.issueDate != null && s.expireDate != null && s.expireDate < s.issueDate) {
            _state.value = s.copy(error = "到期日不能早于签发日")
            return@launch
        }
        _state.value = s.copy(loading = true, error = null)

        if (s.id == 0L) {
            val newId = repo.create(
                title = s.title,
                category = s.category,
                note = s.note,
                issueDate = s.issueDate,
                expireDate = s.expireDate,
                remindDaysBefore = s.remindDaysBefore,
                coverBytes = s.coverBytes
            )
            files.forEach { f ->
                runCatching { repo.addAttachment(newId, f.uri, f.name, f.mime) }
            }
        } else {
            val existing = repo.find(s.id)
            if (existing == null) {
                _state.value = s.copy(loading = false, error = "记录已被删除")
                return@launch
            }
            // 用户本次选了新图 -> 真正替换（并清掉旧加密文件）
            val pickedThisSession = s.coverBytes != null && !s.coverBytes.contentEquals(
                repo.coverBytes(existing) ?: ByteArray(0)
            )
            if (pickedThisSession) {
                repo.replaceCover(existing, s.coverBytes!!)
                repo.update(
                    existing.copy(
                        title = s.title.trim(),
                        category = s.category,
                        note = s.note.trim(),
                        issueDate = s.issueDate,
                        expireDate = s.expireDate,
                        remindDaysBefore = s.remindDaysBefore
                    )
                )
            } else {
                // 没有改图；若用户点了"移除图片"则清空
                val keepPath = if (s.coverBytes == null) null else existing.coverPath
                if (keepPath == null && existing.coverPath != null) {
                    repo.deleteCoverFile(existing)
                }
                repo.update(
                    existing.copy(
                        title = s.title.trim(),
                        category = s.category,
                        note = s.note.trim(),
                        issueDate = s.issueDate,
                        expireDate = s.expireDate,
                        remindDaysBefore = s.remindDaysBefore,
                        coverPath = keepPath
                    )
                )
            }
            files.forEach { f ->
                runCatching { repo.addAttachment(existing.id, f.uri, f.name, f.mime) }
            }
        }
        _state.value = _state.value.copy(loading = false, saved = true)
        onDone()
    }

    private inline fun MutableStateFlow<EditState>.update(block: (EditState) -> EditState) {
        value = block(value)
    }
}
