package com.liuxue.assistant.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 紧急信息：可自定义多条（本人血型/过敏史、紧急联系人、使馆电话、保险单号等）。
 * label + value 的通用结构，保证用户能自定义任意条目。
 */
@Entity(tableName = "emergency_info")
data class EmergencyInfo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 分组：本人信息 / 紧急联系人 / 机构电话 / 其他（group 是 SQL 保留字，需转义） */
    @ColumnInfo(name = "group") val group: String = GROUP_PERSONAL,
    val label: String,
    val value: String,
    /** 补充说明（如关系、备注） */
    val note: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val GROUP_PERSONAL = "personal"
        const val GROUP_CONTACT = "contact"
        const val GROUP_INSTITUTION = "institution"
        const val GROUP_OTHER = "other"
    }
}
