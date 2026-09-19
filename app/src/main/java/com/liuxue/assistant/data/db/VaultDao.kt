package com.liuxue.assistant.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    @Query("SELECT * FROM vault_file ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_file WHERE category = :category ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeByCategory(category: String): Flow<List<VaultFile>>

    /** 标题/备注模糊搜索 */
    @Query("SELECT * FROM vault_file WHERE title LIKE '%' || :q || '%' OR note LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<VaultFile>>

    /** 即将到期（expireDate 在 before 之前且不早于 now） */
    @Query("SELECT * FROM vault_file WHERE expireDate IS NOT NULL AND expireDate BETWEEN :now AND :before ORDER BY expireDate ASC")
    fun observeExpiring(now: Long, before: Long): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_file WHERE expireDate IS NOT NULL AND expireDate < :now ORDER BY expireDate DESC")
    fun observeExpired(now: Long): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_file WHERE id = :id")
    suspend fun findById(id: Long): VaultFile?

    @Query("SELECT * FROM vault_file WHERE expireDate IS NOT NULL")
    suspend fun allWithExpiry(): List<VaultFile>

    @Query("SELECT * FROM vault_file ORDER BY createdAt")
    suspend fun all(): List<VaultFile>

    @Query("SELECT * FROM vault_file WHERE title = :title AND category = :category LIMIT 1")
    suspend fun findDuplicate(title: String, category: String): VaultFile?

    @Insert
    suspend fun insert(item: VaultFile): Long

    @Update
    suspend fun update(item: VaultFile)

    @Delete
    suspend fun delete(item: VaultFile)

    @Query("UPDATE vault_file SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long = System.currentTimeMillis())

    // ---------- 附件 ----------

    @Query("SELECT * FROM vault_attachment WHERE fileId = :fileId ORDER BY createdAt ASC")
    fun observeAttachments(fileId: Long): Flow<List<VaultAttachment>>

    @Query("SELECT * FROM vault_attachment WHERE fileId = :fileId ORDER BY createdAt ASC")
    suspend fun attachmentsOf(fileId: Long): List<VaultAttachment>

    @Insert
    suspend fun insertAttachment(item: VaultAttachment): Long

    @Delete
    suspend fun deleteAttachment(item: VaultAttachment)

    @Query("SELECT COUNT(*) FROM vault_attachment WHERE fileId = :fileId")
    suspend fun attachmentCount(fileId: Long): Int

    @Transaction
    suspend fun deleteWithAttachments(item: VaultFile): List<VaultAttachment> {
        val atts = attachmentsOf(item.id)
        atts.forEach { deleteAttachment(it) }
        delete(item)
        return atts
    }
}

@Dao
interface EmergencyDao {
    @Query("SELECT * FROM emergency_info ORDER BY `group`, sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<EmergencyInfo>>

    @Query("SELECT * FROM emergency_info")
    suspend fun all(): List<EmergencyInfo>

    @Insert
    suspend fun insert(item: EmergencyInfo): Long

    @Update
    suspend fun update(item: EmergencyInfo)

    @Delete
    suspend fun delete(item: EmergencyInfo)
}
