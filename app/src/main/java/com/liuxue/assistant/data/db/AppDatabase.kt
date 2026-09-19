package com.liuxue.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.liuxue.assistant.data.dict.HistoryDao
import com.liuxue.assistant.data.dict.Phrase
import com.liuxue.assistant.data.dict.PhraseCategory
import com.liuxue.assistant.data.dict.PhraseDao
import com.liuxue.assistant.data.dict.SearchHistory
import com.liuxue.assistant.data.dict.WordbookCategory
import com.liuxue.assistant.data.dict.WordbookDao
import com.liuxue.assistant.data.dict.WordbookItem
import com.liuxue.assistant.data.study.Course
import com.liuxue.assistant.data.study.CourseMaterial
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Exam
import com.liuxue.assistant.data.study.Homework
import com.liuxue.assistant.data.study.HomeworkAttachment
import com.liuxue.assistant.data.study.Semester
import com.liuxue.assistant.data.study.StudyDao

@Database(
    entities = [
        VaultFile::class, VaultAttachment::class, EmergencyInfo::class,
        WordbookItem::class, WordbookCategory::class,
        Phrase::class, PhraseCategory::class, SearchHistory::class,
        Semester::class, Course::class, CourseSchedule::class,
        Homework::class, Exam::class, CourseMaterial::class, Memo::class,
        HomeworkAttachment::class, MemoAttachment::class, AiQa::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao
    abstract fun emergencyDao(): EmergencyDao
    abstract fun wordbookDao(): WordbookDao
    abstract fun phraseDao(): PhraseDao
    abstract fun historyDao(): HistoryDao
    abstract fun studyDao(): StudyDao
    abstract fun memoDao(): MemoDao
    abstract fun aiQaDao(): AiQaDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2（保留用户数据，绝不重建库）：
         *  - course 增加 link 列（网课链接）
         *  - 新增 memo 表（备忘录，正文密文存 Base64）
         */
        /** v2 -> v3：作业附件表（作业可上传任意文件，图片可显示预览图） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `homework_attachment` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`homeworkId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, " +
                        "`mime` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_homework_attachment_homeworkId` " +
                        "ON `homework_attachment` (`homeworkId`)"
                )
            }
        }

        /** v3 -> v4：备忘录附件表 + AI 问答历史表 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memo_attachment` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`memoId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, " +
                        "`mime` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memo_attachment_memoId` " +
                        "ON `memo_attachment` (`memoId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_qa` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`question` TEXT NOT NULL, " +
                        "`answer` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course ADD COLUMN link TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memo` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`bodyCipher` TEXT NOT NULL, " +
                        "`pinned` INTEGER NOT NULL, " +
                        "`remindAt` INTEGER, " +
                        "`notifiedFor` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        /** 打开失败时把坏库改名备份，用户数据文件（vault 下的图片）不受影响 */
        private fun renameBroken(context: Context) {
            val f = context.getDatabasePath("assistant.db")
            if (f.exists()) {
                val bak = java.io.File(f.parentFile, "assistant.db.broken." + System.currentTimeMillis())
                runCatching { f.renameTo(bak) }
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): AppDatabase {
            val builder = {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "assistant.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
            }
            return try {
                builder()
            } catch (e: Exception) {
                // 绝不因为数据库打不开就让 App 崩；先把坏库留档，再建新库。
                // 注意：vault 目录里的加密图片不受影响，用户仍可重新录入元数据后恢复查看。
                android.util.Log.e("AppDatabase", "数据库打开失败，已备份旧库", e)
                renameBroken(context)
                builder()
            }
        }
    }
}
