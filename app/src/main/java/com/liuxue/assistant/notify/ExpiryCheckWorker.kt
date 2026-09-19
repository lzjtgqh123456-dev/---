package com.liuxue.assistant.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.domain.ExpiryLevel
import com.liuxue.assistant.util.DateUtils

/**
 * 每天跑一次：扫描所有带到期日的证件，按剩余天数分级提醒。
 * 用 WorkManager 而不是精确闹钟，省电且不需要 SCHEDULE_EXACT_ALARM 权限。
 */
class ExpiryCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val items = db.vaultDao().allWithExpiry()
        if (items.isEmpty()) return Result.success()

        val expired = mutableListOf<String>()
        val urgent = mutableListOf<String>()
        val warning = mutableListOf<String>()

        items.forEach { f ->
            val level = ExpiryLevel.of(f.expireDate, f.remindDaysBefore)
            if (!ExpiryLevel.shouldNotify(f.expireDate, f.remindDaysBefore)) return@forEach
            val line = "${f.title}：${DateUtils.humanRemaining(f.expireDate!!)}（${DateUtils.formatDay(f.expireDate)}）"
            when (level) {
                ExpiryLevel.EXPIRED -> expired += line
                ExpiryLevel.URGENT -> urgent += line
                else -> warning += line
            }
        }

        if (expired.isNotEmpty()) {
            Notifications.notifySummary(
                applicationContext, Notifications.CHANNEL_EXPIRY, ID_EXPIRED,
                "⛔ ${expired.size} 个证件已过期", expired
            )
        }
        val soon = urgent + warning
        if (soon.isNotEmpty()) {
            Notifications.notifySummary(
                applicationContext, Notifications.CHANNEL_EXPIRY, ID_SOON,
                "⏰ ${soon.size} 个证件即将到期", soon
            )
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "expiry_daily_check"
        const val ID_EXPIRED = 1001
        const val ID_SOON = 1002
    }
}
