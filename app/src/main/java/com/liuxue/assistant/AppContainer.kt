package com.liuxue.assistant

import android.content.Context
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.dict.DictRepository
import com.liuxue.assistant.data.repo.VaultRepository
import com.liuxue.assistant.data.study.StudyRepository

/**
 * 极简依赖容器：本项目规模用不到 Hilt，手写单例更直观、构建更快。
 */
object AppContainer {

    @Volatile private var db: AppDatabase? = null
    @Volatile private var vaultRepo: VaultRepository? = null

    fun database(context: Context): AppDatabase =
        db ?: synchronized(this) {
            db ?: AppDatabase.get(context).also { db = it }
        }

    fun vaultRepository(context: Context): VaultRepository =
        vaultRepo ?: synchronized(this) {
            vaultRepo ?: VaultRepository(context.applicationContext, database(context))
                .also { vaultRepo = it }
        }

    @Volatile private var studyRepo: StudyRepository? = null

    fun studyRepository(context: Context): StudyRepository =
        studyRepo ?: synchronized(this) {
            studyRepo ?: StudyRepository(context.applicationContext, database(context))
                .also { studyRepo = it }
        }

    @Volatile private var dictRepo: DictRepository? = null

    fun dictRepository(context: Context): DictRepository =
        dictRepo ?: synchronized(this) {
            dictRepo ?: DictRepository(context.applicationContext, database(context))
                .also { dictRepo = it }
        }
}
