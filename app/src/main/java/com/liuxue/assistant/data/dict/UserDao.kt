package com.liuxue.assistant.data.dict

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WordbookDao {
    @Query("""
        SELECT w.* FROM wordbook_item w
        WHERE (:categoryId < 0 OR w.categoryId = :categoryId)
        ORDER BY w.addedAt DESC
    """)
    fun observe(categoryId: Long = -1L): Flow<List<WordbookItem>>

    @Query("SELECT * FROM wordbook_item WHERE entryId = :entryId")
    suspend fun find(entryId: Long): WordbookItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WordbookItem): Long

    @Query("DELETE FROM wordbook_item WHERE entryId = :entryId")
    suspend fun remove(entryId: Long)

    @Query("SELECT COUNT(*) FROM wordbook_item")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM wordbook_category ORDER BY sortOrder, id")
    fun observeCategories(): Flow<List<WordbookCategory>>

    @Query("SELECT * FROM wordbook_item")
    suspend fun allItems(): List<WordbookItem>

    @Query("SELECT * FROM wordbook_category")
    suspend fun allCategories(): List<WordbookCategory>

    @Insert
    suspend fun addCategory(c: WordbookCategory): Long

    @Query("DELETE FROM wordbook_category WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    /** 到期需复习的 */
    @Query("SELECT * FROM wordbook_item WHERE dueAt <= :now ORDER BY dueAt LIMIT :limit")
    suspend fun due(now: Long, limit: Int = 20): List<WordbookItem>
}

@Dao
interface PhraseDao {
    @Query("SELECT * FROM phrase ORDER BY starred DESC, createdAt DESC")
    fun observeAll(): Flow<List<Phrase>>

    @Query("SELECT * FROM phrase WHERE categoryId = :categoryId ORDER BY starred DESC, createdAt DESC")
    fun observeByCategory(categoryId: Long): Flow<List<Phrase>>

    @Insert
    suspend fun insert(p: Phrase): Long

    @Query("UPDATE phrase SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("DELETE FROM phrase WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM phrase_category ORDER BY sortOrder, id")
    fun observeCategories(): Flow<List<PhraseCategory>>

    @Query("SELECT * FROM phrase ORDER BY createdAt")
    suspend fun all(): List<Phrase>

    @Query("SELECT * FROM phrase_category")
    suspend fun allCategories(): List<PhraseCategory>

    @Query("SELECT * FROM phrase WHERE categoryId = :cid AND textRu = :ru LIMIT 1")
    suspend fun findDuplicate(cid: Long, ru: String): Phrase?

    @Insert
    suspend fun addCategory(c: PhraseCategory): Long

    @Query("DELETE FROM phrase_category WHERE id = :id")
    suspend fun deleteCategory(id: Long)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun add(h: SearchHistory)

    @Query("SELECT * FROM search_history ORDER BY at DESC LIMIT 50")
    fun observe(): Flow<List<SearchHistory>>

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
