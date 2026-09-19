package com.liuxue.assistant.data.translate

/**
 * 离线翻译模型元信息。
 *
 * 设计要点：
 * - 模型**不打进 APK**（1.06 GB 会让安装包不可接受），改为**首次使用时下载**
 * - 下载源提供多个镜像，按顺序尝试，任一可用即可
 * - 带 SHA-256 校验，防止下载损坏导致运行崩溃
 */
object TranslationModel {

    /** 模型文件名（落在应用外部私有目录） */
    const val FILE_NAME = "HY-MT1.5-1.8B-Q4_K_M.gguf"

    /** 量化格式说明：Q4_K_M 在 1.8B 上质量与体积平衡最好 */
    const val QUANT = "Q4_K_M"

    /** 预期大小（字节），用于校验与显示 */
    const val EXPECTED_SIZE = 1_133_080_512L

    /** 预期 SHA-256（小写）。为空则跳过校验（首次发布未固定时） */
    const val EXPECTED_SHA256 = ""

    /** 下载源，按顺序尝试 */
    val MIRRORS = listOf(
        "https://hf-mirror.com/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf",
        "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf"
    )

    /** 模型能力说明，用于 App 内展示 */
    val SUPPORTED_LANGUAGES = listOf(
        "中文" to "zh", "英语" to "en", "俄语" to "ru", "日语" to "ja",
        "韩语" to "ko", "法语" to "fr", "德语" to "de", "西班牙语" to "es",
        "葡萄牙语" to "pt", "意大利语" to "it", "阿拉伯语" to "ar", "泰语" to "th"
    )

    /** 内存建议：Q4_K_M 约需 2 GB 可用内存（含 KV cache 与运行时开销） */
    const val MIN_RAM_MB = 2048

    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }
}

/** 模型状态 */
sealed interface ModelStatus {
    /** 未下载 */
    data object NotDownloaded : ModelStatus
    /** 下载中 */
    data class Downloading(
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Long,
        val mirrorIndex: Int
    ) : ModelStatus {
        val percent: Int get() = if (total <= 0) 0 else ((downloaded * 100) / total).toInt()
    }
    /** 已就绪，path 为模型文件绝对路径 */
    data class Ready(val path: String, val sizeBytes: Long) : ModelStatus
    /** 出错 */
    data class Failed(val message: String) : ModelStatus
}
