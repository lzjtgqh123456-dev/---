package com.liuxue.assistant.feature.transfer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TransferState(
    /** 导出内容选择 */
    val includeWordbook: Boolean = true,
    val includePhrases: Boolean = true,
    val includeVault: Boolean = true,
    val includeVaultImages: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    /** 导出后待分享的文件 */
    val shareFile: File? = null
)

/**
 * 数据导入导出。
 *
 * 导出：把生词本/短语集/证件打包成 .lex 文件，写到 cache 目录后通过 FileProvider 分享。
 * 导入：从系统文件选择器读取 .lex，合并进本地数据。
 */
class TransferViewModel(app: Application) : AndroidViewModel(app) {

    private val manager: BackupManager by lazy {
        BackupManager(
            context = app.applicationContext,
            db = AppContainer.database(app),
            vaultRepo = AppContainer.vaultRepository(app),
            appVersion = "0.2.0"
        )
    }

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    fun toggleWordbook() = _state.update { it.copy(includeWordbook = !it.includeWordbook) }
    fun togglePhrases() = _state.update { it.copy(includePhrases = !it.includePhrases) }
    fun toggleVault() = _state.update { it.copy(includeVault = !it.includeVault) }
    fun toggleVaultImages() = _state.update { it.copy(includeVaultImages = !it.includeVaultImages) }
    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearShareFile() = _state.update { it.copy(shareFile = null) }

    /** 导出到 cache 文件，供分享 */
    fun export() = viewModelScope.launch {
        val s = _state.value
        if (!s.includeWordbook && !s.includePhrases && !s.includeVault) {
            _state.update { it.copy(message = "请至少选择一项要导出的内容") }
            return@launch
        }
        _state.update { it.copy(busy = true, message = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val json = manager.exportToJson(
                    ExportOptions(
                        wordbook = s.includeWordbook,
                        phrases = s.includePhrases,
                        vault = s.includeVault,
                        vaultImages = s.includeVaultImages
                    )
                )
                val dir = File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                    .format(java.util.Date())
                val f = File(dir, "留学助手备份_$stamp.${BackupFormat.EXTENSION}")
                f.writeText(json, Charsets.UTF_8)
                f
            }
        }
        result.onSuccess { f ->
            _state.update {
                it.copy(busy = false, shareFile = f, message = "已生成备份：" + f.name)
            }
        }.onFailure { e ->
            _state.update {
                it.copy(busy = false, message = "导出失败：" + (e.message ?: e::class.java.simpleName))
            }
        }
    }

    /** 从 Uri 导入 */
    fun import(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("无法读取所选文件")
                manager.importFromJson(text)
            }
        }
        result.onSuccess { r ->
            _state.update {
                it.copy(busy = false, message = r.error ?: ("导入完成：" + r.summary()))
            }
        }.onFailure { e ->
            _state.update {
                it.copy(busy = false, message = "导入失败：" + (e.message ?: e::class.java.simpleName))
            }
        }
    }

    private inline fun MutableStateFlow<TransferState>.update(block: (TransferState) -> TransferState) {
        value = block(value)
    }
}
