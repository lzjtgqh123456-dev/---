package com.liuxue.assistant.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.liuxue.assistant.R

object Notifications {

    const val CHANNEL_EXPIRY = "expiry_reminder"
    const val CHANNEL_STUDY = "study_reminder"
    const val CHANNEL_MEMO = "memo_reminder"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EXPIRY,
                "证件到期提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "证件、签证等到期前的提醒" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STUDY,
                "课业提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "上课、作业、考试提醒" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MEMO,
                "备忘录提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "备忘录到点提醒" }
        )
    }

    /** 发送提醒。需要 POST_NOTIFICATIONS 权限（Android 13+） */
    fun notify(context: Context, channel: String, id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, n)
        }
    }

    /** 汇总提醒（多条时合并成一条，避免刷屏） */
    fun notifySummary(context: Context, channel: String, id: Int, title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n"))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
