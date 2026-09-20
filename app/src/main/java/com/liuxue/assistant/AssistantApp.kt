package com.liuxue.assistant

import android.app.Application
import com.liuxue.assistant.notify.ReminderScheduler

class AssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 注册后台提醒任务（幂等）
        ReminderScheduler.scheduleAll(this)
        // 信封加密的 DEK 需要落盘位置
        com.liuxue.assistant.data.security.VaultCrypto.init(this)
        // 清掉上次留下的明文缓存（打开/分享加密文件时会临时解密到 cache/vault_preview）
        Thread {
            runCatching { com.liuxue.assistant.data.security.VaultStore(this).clearCache() }
        }.start()
    }
}
