package com.liuxue.assistant.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 备忘录。
 *
 * 正文用 VaultCrypto（Android Keystore 里的 AES-256-GCM）加密后以 Base64 存库，
 * **明文正文不落盘**；标题保持明文，方便列表搜索与排序。
 */
@Entity(tableName = "memo")
data class Memo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 标题（明文，便于搜索） */
    val title: String,
    /** 正文密文（Base64） */
    val bodyCipher: String = "",
    val pinned: Boolean = false,
    /** 提醒时间（毫秒），null = 不提醒 */
    val remindAt: Long? = null,
    /** 已经为哪个提醒时间发过通知（去重，避免重复弹） */
    val notifiedFor: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MemoDao {
    @Query("SELECT * FROM memo ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<Memo>>

    @Query("SELECT * FROM memo WHERE id = :id")
    suspend fun byId(id: Long): Memo?

    @Query(
        "SELECT * FROM memo WHERE remindAt IS NOT NULL AND remindAt <= :now " +
            "AND (notifiedFor IS NULL OR notifiedFor != remindAt)"
    )
    suspend fun dueReminders(now: Long): List<Memo>

    @Insert
    suspend fun insert(m: Memo): Long

    @Update
    suspend fun update(m: Memo)

    @Delete
    suspend fun delete(m: Memo)

    @Query("UPDATE memo SET notifiedFor = :remindAt WHERE id = :id")
    suspend fun markNotified(id: Long, remindAt: Long)

    // ---------- 附件 ----------

    @Query("SELECT * FROM memo_attachment WHERE memoId = :memoId ORDER BY createdAt")
    suspend fun attachments(memoId: Long): List<MemoAttachment>

    @Query("SELECT * FROM memo_attachment ORDER BY createdAt")
    suspend fun allAttachments(): List<MemoAttachment>

    @Insert
    suspend fun insertAttachment(a: MemoAttachment): Long

    @Delete
    suspend fun deleteAttachment(a: MemoAttachment)
}


/** 备忘录附件：任意文件，加密保存（复用 VaultStore） */
@Entity(tableName = "memo_attachment", indices = [Index("memoId")])
data class MemoAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val memoId: Long,
    val name: String,
    val path: String,
    val mime: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
