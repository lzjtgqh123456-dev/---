package com.liuxue.assistant.data.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地推理桥接（JNI -> llama.cpp）。
 *
 * 原生库由 NDK 自行编译为精简版（只含 llama + ggml 推理必需部分），
 * strip 后仅约 5.6 MB —— 对比官方预编译包的 100 MB（其中 80 MB 是 CLI 公共代码）。
 *
 * 模型仍**首次使用时下载**（1.06 GB），不打进 APK。
 */
object LlamaBridge {

    @Volatile private var loaded = false

    private external fun nativeInit(modelPath: String, nThreads: Int): Boolean
    private external fun nativeGenerate(
        systemPrompt: String,
        userText: String,
        maxTokens: Int
    ): String
    private external fun nativeCancel()
    private external fun nativeRelease()

    /** 原生库是否可用（未打包对应 ABI 时为 false，需优雅降级） */
    val isAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("llama_jni")
            loaded = true
            true
        }.getOrElse {
            android.util.Log.e("LlamaBridge", "原生库加载失败", it)
            false
        }
    }

    /** 加载模型（耗时数秒，务必在 IO 线程） */
    suspend fun init(modelPath: String, threads: Int = 4): Boolean =
        withContext(Dispatchers.IO) {
            if (!isAvailable) return@withContext false
            runCatching { nativeInit(modelPath, threads) }.getOrDefault(false)
        }

    /** 生成译文（挂起，内部阻塞在原生解码） */
    suspend fun translate(
        systemPrompt: String,
        text: String,
        maxTokens: Int = 1024
    ): String = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext ""
        runCatching { nativeGenerate(systemPrompt, text, maxTokens) }.getOrElse { "" }
    }

    /** 请求中断当前生成 */
    fun cancel() {
        if (isAvailable) runCatching { nativeCancel() }
    }

    /** 释放模型与上下文，回收内存 */
    fun release() {
        if (isAvailable && loaded) runCatching { nativeRelease() }
        loaded = false
    }

    /** 语言代码 -> 翻译指令（Hy-MT1.5 按"目标语言"给指令，效果最稳） */
    fun languageName(code: String): String = when (code) {
        "zh" -> "Chinese"
        "en" -> "English"
        "ru" -> "Russian"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "fr" -> "French"
        "de" -> "German"
        "es" -> "Spanish"
        "pt" -> "Portuguese"
        "it" -> "Italian"
        "ar" -> "Arabic"
        "th" -> "Thai"
        else -> "English"
    }

    fun systemPrompt(targetCode: String): String =
        "Translate the following segment into " + languageName(targetCode) +
            ", without additional explanation."
}
