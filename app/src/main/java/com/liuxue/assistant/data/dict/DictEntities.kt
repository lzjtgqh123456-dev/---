package com.liuxue.assistant.data.dict

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 生词本条目（用户数据，可写） */
@Entity(
    tableName = "wordbook_item",
    indices = [Index("entryId"), Index("categoryId"), Index(value = ["entryId", "categoryId"], unique = true)]
)
data class WordbookItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val entryId: Long,
    val categoryId: Long = 0L,
    /** 掌握程度 0=新词 1=模糊 2=熟悉 3=已掌握 */
    val mastery: Int = 0,
    val note: String = "",
    val starred: Boolean = false,
    /** 下次复习时间（间隔重复用） */
    val dueAt: Long = System.currentTimeMillis(),
    val addedAt: Long = System.currentTimeMillis()
)

/** 生词本分类（用户可自定义） */
@Entity(tableName = "wordbook_category")
data class WordbookCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val colorHex: String = "#1E6F5C",
    val sortOrder: Int = 0
)

/** 自定义常用短语 */
@Entity(tableName = "phrase", indices = [Index("categoryId")])
data class Phrase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val textRu: String,
    val textZh: String,
    val note: String = "",
    val categoryId: Long = 0L,
    val starred: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** 短语分类 */
@Entity(tableName = "phrase_category")
data class PhraseCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sortOrder: Int = 0
)

/** 查词历史 */
@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val query: String,
    val entryId: Long?,
    val at: Long = System.currentTimeMillis()
)
