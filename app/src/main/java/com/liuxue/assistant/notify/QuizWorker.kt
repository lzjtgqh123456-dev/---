package com.liuxue.assistant.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.dict.QuizSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日生词抽查提醒。
 *
 * 到点后从生词本里随机抽 N 个词（默认优先未掌握的），发一条通知；
 * 点通知进入 App 的抽查页（深链 liuxue://quiz）。
 *
 * 不在通知里直接显示词义：抽查的意义就是让用户自己回忆。
 */
class QuizWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = QuizSettings(applicationContext)
        if (!settings.enabled) return Result.success()

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        // 一天只提醒一次
        if (settings.lastQuizDate == today) return Result.success()

        val db = AppDatabase.get(applicationContext)
        val dao = db.wordbookDao()
        val items = dao.allItems()
        if (items.isEmpty()) return Result.success()

        val pool = if (settings.onlyUnmastered) items.filter { it.mastery < 3 } else items
        if (pool.isEmpty()) {
            // 全部已掌握：给一句鼓励，不再打扰
            Notifications.notify(
                applicationContext,
                Notifications.CHANNEL_STUDY,
                ID_QUIZ,
                "生词本已全部掌握 🎉",
                "共 " + items.size + " 个词都标记为已掌握了，很棒！"
            )
            settings.lastQuizDate = today
            return Result.success()
        }

        val picked = pool.shuffled().take(settings.dailyCount)
        Notifications.notify(
            applicationContext,
            Notifications.CHANNEL_STUDY,
            ID_QUIZ,
            "今日生词抽查：" + picked.size + " 个词",
            "点开开始抽查。认识就标「已掌握」，不认识标「模糊」，明天会再抽到你。"
        )
        settings.lastQuizDate = today
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "daily_quiz"
        const val ID_QUIZ = 3001
    }
}
