package com.liuxue.assistant.data.repo

import android.content.Context
import android.net.Uri
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.db.EmergencyInfo
import com.liuxue.assistant.data.db.VaultAttachment
import com.liuxue.assistant.data.db.VaultFile
import com.liuxue.assistant.data.security.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 证件模块总入口：数据库 + 加密文件存储的统一门面。
 * 所有写操作都在 IO 线程，UI 层只消费 Flow。
 */
class VaultRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val store: VaultStore = VaultStore(context)
) {
    private val dao = db.vaultDao()
    private val emergencyDao = db.emergencyDao()

    // ---------- 查询 ----------

    fun observeAll(): Flow<List<VaultFile>> = dao.observeAll()

    fun observeByCategory(categoryKey: String): Flow<List<VaultFile>> =
        dao.observeByCategory(categoryKey)

    fun search(query: String): Flow<List<VaultFile>> = dao.search(query)

    fun observeExpiring(days: Int = 90): Flow<List<VaultFile>> {
        val now = System.currentTimeMillis()
        return dao.observeExpiring(now, now + days * 24L * 3600 * 1000)
    }

    fun observeExpired(): Flow<List<VaultFile>> = dao.observeExpired(System.currentTimeMillis())

    suspend fun find(id: Long): VaultFile? = dao.findById(id)

    suspend fun attachments(fileId: Long): List<VaultAttachment> = dao.attachmentsOf(fileId)

    suspend fun attachmentCount(fileId: Long): Int = dao.attachmentCount(fileId)

    // ---------- 写入 ----------

    suspend fun create(
        title: String,
        category: String,
        note: String,
        issueDate: Long?,
        expireDate: Long?,
        remindDaysBefore: Int,
        coverBytes: ByteArray?
    ): Long = withContext(Dispatchers.IO) {
        val coverPath = coverBytes?.let { store.saveBytes(it, "jpg") }
        dao.insert(
            VaultFile(
                title = title.trim(),
                category = category,
                note = note.trim(),
                coverPath = coverPath,
                issueDate = issueDate,
                expireDate = expireDate,
                remindDaysBefore = remindDaysBefore
            )
        )
    }

    suspend fun update(item: VaultFile) = withContext(Dispatchers.IO) {
        dao.update(item.copy(updatedAt = System.currentTimeMillis()))
    }

    /** 替换主图（旧的加密文件会被删除，避免残留占空间） */
    suspend fun replaceCover(item: VaultFile, newBytes: ByteArray): VaultFile =
        withContext(Dispatchers.IO) {
            item.coverPath?.let { store.delete(it) }
            val path = store.saveBytes(newBytes, "jpg")
            val updated = item.copy(coverPath = path, updatedAt = System.currentTimeMillis())
            dao.update(updated)
            updated
        }

    /** 删除主图加密文件（用于编辑页"移除图片"） */
    suspend fun deleteCoverFile(item: VaultFile) = withContext(Dispatchers.IO) {
        item.coverPath?.let { store.delete(it) }
        dao.update(item.copy(coverPath = null, updatedAt = System.currentTimeMillis()))
    }

    /** 从系统选择器导入附件 */
    suspend fun addAttachment(fileId: Long, uri: Uri, displayName: String, mime: String): Long =
        withContext(Dispatchers.IO) {
            val ext = displayName.substringAfterLast('.', "bin").take(8)
            val path = context.contentResolver.openInputStream(uri)?.use { input ->
                store.saveStream(input, ext)
            } ?: error("无法读取所选文件")
            val size = File(context.filesDir, "vault/$path").length()
            dao.insertAttachment(
                VaultAttachment(
                    fileId = fileId,
                    name = displayName,
                    path = path,
                    mime = mime,
                    sizeBytes = size
                )
            )
        }

    suspend fun deleteAttachment(att: VaultAttachment) = withContext(Dispatchers.IO) {
        store.delete(att.path)
        dao.deleteAttachment(att)
    }

    /** 删除记录：连带清理全部加密文件，保证不留垃圾 */
    suspend fun delete(item: VaultFile) = withContext(Dispatchers.IO) {
        val atts = dao.deleteWithAttachments(item)
        atts.forEach { store.delete(it.path) }
        item.coverPath?.let { store.delete(it) }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        dao.setPinned(id, pinned)
    }

    // ---------- 文件读取 ----------

    suspend fun coverBytes(item: VaultFile): ByteArray? = withContext(Dispatchers.IO) {
        item.coverPath?.let { store.readBytes(it) }
    }

    suspend fun attachmentBytes(att: VaultAttachment): ByteArray? = withContext(Dispatchers.IO) {
        store.readBytes(att.path)
    }

    /** 按加密相对路径读取明文（导出图片时用） */
    suspend fun coverBytesByPath(path: String): ByteArray? = withContext(Dispatchers.IO) {
        store.readBytes(path)
    }

    /** 解密成真实文件，供分享/外部查看 */
    suspend fun materializeForShare(path: String, name: String): File? =
        withContext(Dispatchers.IO) {
            val ext = name.substringAfterLast('.', "jpg").take(8)
            store.materialize(path, ext)?.let { plain ->
                val named = File(plain.parentFile, name)
                if (plain.renameTo(named)) named else plain
            }
        }

    /** 导出为明文（用户主动导出时使用，会明确提示风险） */
    suspend fun exportPlain(bytes: ByteArray, target: java.io.OutputStream) =
        withContext(Dispatchers.IO) {
            target.use { it.write(bytes) }
        }

    suspend fun vaultSize(): Long = withContext(Dispatchers.IO) { store.totalSize() }

    fun clearPreviewCache() = store.clearCache()

    // ---------- 紧急信息 ----------

    fun observeEmergency(): Flow<List<EmergencyInfo>> = emergencyDao.observeAll()

    suspend fun saveEmergency(item: EmergencyInfo) = withContext(Dispatchers.IO) {
        if (item.id == 0L) emergencyDao.insert(item) else emergencyDao.update(item)
    }

    suspend fun deleteEmergency(item: EmergencyInfo) = withContext(Dispatchers.IO) {
        emergencyDao.delete(item)
    }
}
