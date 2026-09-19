package com.liuxue.assistant.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liuxue.assistant.data.repo.MemoRepository

/**
 * 备忘录提醒：每 15 分钟检查一次「到点的备忘录」，发通知。
 * 用 notifiedFor 字段去重，同一条提醒只弹一次（改了提醒时间会重新弹）。
 */
class MemoReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = MemoRepository(applicationContext)
        val now = System.currentTimeMillis()
        val due = repo.dueReminders(now)
        due.forEach { m ->
            val body = repo.decryptBody(m.bodyCipher).take(300)
            Notifications.notify(
                applicationContext,
                Notifications.CHANNEL_MEMO,
                (2000 + (m.id % 1000)).toInt(),
                "📝 " + m.title.ifBlank { "备忘录" },
                body.ifBlank { "到时间了，点开查看" }
            )
            repo.markNotified(m.id, m.remindAt ?: now)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "memo_reminder_check"
    }
}
