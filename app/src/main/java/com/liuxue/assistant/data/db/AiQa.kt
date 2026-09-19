package com.liuxue.assistant.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** AI 问答历史（存问题 + 回答，可点回去继续看） */
@Entity(tableName = "ai_qa")
data class AiQa(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val question: String,
    val answer: String,
    val at: Long = System.currentTimeMillis()
)

@Dao
interface AiQaDao {
    @Query("SELECT * FROM ai_qa ORDER BY at DESC LIMIT 200")
    fun observeAll(): Flow<List<AiQa>>

    @Insert
    suspend fun insert(q: AiQa): Long

    @Query("DELETE FROM ai_qa")
    suspend fun clear()
}
