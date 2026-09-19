package com.liuxue.assistant.data.repo

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.db.Memo
import com.liuxue.assistant.data.db.MemoAttachment
import com.liuxue.assistant.data.security.VaultStore
import com.liuxue.assistant.data.security.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备忘录门面：正文加密/解密 + 增删改查 + 到期提醒查询。
 * 复用证件模块的 [VaultCrypto]（AES-256-GCM，密钥在 Android Keystore）。
 */
class MemoRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).memoDao()
    private val store = VaultStore(context)

    fun observeAll(): Flow<List<Memo>> = dao.observeAll()

    /** 加密正文；空正文直接返回空串 */
    fun encryptBody(plain: String): String {
        if (plain.isEmpty()) return ""
        val bytes = VaultCrypto.encrypt(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 解密正文；密钥失效等异常时返回提示而不是崩溃 */
    fun decryptBody(cipher: String): String {
        if (cipher.isEmpty()) return ""
        return runCatching {
            String(VaultCrypto.decrypt(Base64.decode(cipher, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse { "（解密失败：密钥可能已失效）" }
    }

    suspend fun get(id: Long): Memo? = withContext(Dispatchers.IO) { dao.byId(id) }

    suspend fun save(m: Memo): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (m.id == 0L) {
            dao.insert(m.copy(createdAt = now, updatedAt = now))
        } else {
            dao.update(m.copy(updatedAt = now))
            m.id
        }
    }

    suspend fun delete(m: Memo) = withContext(Dispatchers.IO) {
        // 连带删除附件与加密文件
        dao.attachments(m.id).forEach { a ->
            store.delete(a.path)
            dao.deleteAttachment(a)
        }
        dao.delete(m)
    }

    // ---------- 附件（任意文件，加密保存） ----------

    suspend fun attachments(memoId: Long): List<MemoAttachment> =
        withContext(Dispatchers.IO) { dao.attachments(memoId) }

    suspend fun addAttachment(memoId: Long, uri: Uri, displayName: String, mime: String): Long =
        withContext(Dispatchers.IO) {
            val ext = displayName.substringAfterLast('.', "bin").take(8)
            val path = context.contentResolver.openInputStream(uri)?.use { input ->
                store.saveStream(input, ext)
            } ?: error("无法读取所选文件")
            val size = java.io.File(context.filesDir, "vault/$path").length()
            dao.insertAttachment(
                MemoAttachment(
                    memoId = memoId,
                    name = displayName,
                    path = path,
                    mime = mime,
                    sizeBytes = size
                )
            )
        }

    suspend fun deleteAttachment(a: MemoAttachment) = withContext(Dispatchers.IO) {
        store.delete(a.path)
        dao.deleteAttachment(a)
    }

    suspend fun attachmentBytes(a: MemoAttachment): ByteArray? =
        withContext(Dispatchers.IO) { store.readBytes(a.path) }

    suspend fun materializeAttachmentForShare(a: MemoAttachment): File? =
        withContext(Dispatchers.IO) {
            val ext = a.name.substringAfterLast('.', "bin").take(8)
            store.materialize(a.path, ext)?.let { plain ->
                val named = File(plain.parentFile, a.name)
                if (plain.renameTo(named)) named else plain
            }
        }

    suspend fun dueReminders(now: Long): List<Memo> =
        withContext(Dispatchers.IO) { dao.dueReminders(now) }

    suspend fun markNotified(id: Long, at: Long) =
        withContext(Dispatchers.IO) { dao.markNotified(id, at) }
}
