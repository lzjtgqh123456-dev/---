package com.liuxue.assistant.data.net

import android.content.Context
import com.liuxue.assistant.data.db.AiQa
import com.liuxue.assistant.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** AI 问答历史：存问题 + 回答，可点历史回到当时的问答 */
class AiHistoryRepository(context: Context) {
    private val dao = AppDatabase.get(context).aiQaDao()

    fun observeAll(): Flow<List<AiQa>> = dao.observeAll()

    suspend fun add(question: String, answer: String) = withContext(Dispatchers.IO) {
        if (question.isBlank() || answer.isBlank()) return@withContext
        dao.insert(AiQa(question = question, answer = answer))
    }

    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }
}
