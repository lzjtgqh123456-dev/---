package com.liuxue.assistant.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.liuxue.assistant.domain.VaultCategory

/**
 * 证件 / 文件记录。
 * 图片与附件本体加密存放在 filesDir/vault 下，本表只保存元数据与加密相对路径。
 */
@Entity(
    tableName = "vault_file",
    indices = [Index("category"), Index("expireDate")]
)
data class VaultFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    /** 分类 key，见 VaultCategory */
    val category: String = VaultCategory.OTHER.key,
    val note: String = "",
    /** 加密主图相对路径（可为空） */
    val coverPath: String? = null,
    val issueDate: Long? = null,
    val expireDate: Long? = null,
    /** 到期前多少天开始提醒；-1 表示不提醒 */
    val remindDaysBefore: Int = 30,
    /** 是否置顶/收藏 */
    val pinned: Boolean = false,
    /** 手动排序序位 */
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 附件（一个证件可附多张图/文件） */
@Entity(
    tableName = "vault_attachment",
    indices = [Index("fileId")]
)
data class VaultAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fileId: Long,
    val name: String,
    /** 加密文件相对路径 */
    val path: String,
    val mime: String = "image/jpeg",
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
