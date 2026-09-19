package com.liuxue.assistant.data.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线翻译模型的管理：检测 / 下载 / 删除。
 *
 * 为什么首次使用时下载而不是打进 APK：
 *   模型 1.06 GB，打进包会让安装包体积不可接受（当前 App 本体只有 ~20 MB）。
 *
 * 下载可靠性设计：
 * - **多镜像回退**：国内 hf-mirror 优先，失败自动换 HuggingFace 官方
 * - **断点续传**：用 Range 请求从已下载字节处继续，中断不用重来
 * - **进度回调**：实时上报已下载/总量/速率，UI 可显示进度条与剩余时间
 * - **落盘后用临时文件改名的原子语义**：只有完整下载才改名为正式文件，避免半截文件被当成可用模型
 */
class ModelRepository(private val context: Context) {

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    @Volatile private var cancelled = false

    /** 模型存放目录：应用外部私有目录，卸载即清理，也不需要存储权限 */
    private fun modelDir(): File =
        File(context.getExternalFilesDir(null), "models").apply { if (!exists()) mkdirs() }

    fun modelFile(): File = File(modelDir(), TranslationModel.FILE_NAME)

    fun modelPath(): String = modelFile().absolutePath

    private fun tempFile(): File = File(modelDir(), TranslationModel.FILE_NAME + ".part")

    /**
     * 检查模型是否已就绪。
     * 判定标准：文件存在且大小与预期一致（容差 1%）；无预期大小时只要求 > 100 MB。
     */
    fun refreshStatus() {
        val f = modelFile()
        if (!f.exists()) {
            _status.value = ModelStatus.NotDownloaded
            return
        }
        val size = f.length()
        val expected = TranslationModel.EXPECTED_SIZE
        val ok = if (expected > 0) {
            kotlin.math.abs(size - expected) <= expected / 100
        } else {
            size > 100L * 1024 * 1024
        }
        _status.value = if (ok) {
            ModelStatus.Ready(f.absolutePath, size)
        } else {
            // 大小不符：可能是上次下载中断留下的残缺文件，删掉重下
            runCatching { f.delete() }
            ModelStatus.NotDownloaded
        }
    }

    /** 已下载的体积（含未完成的 .part），用于显示"继续下载" */
    fun downloadedBytes(): Long =
        maxOf(modelFile().length(), tempFile().length())

    fun cancel() { cancelled = true }

    /** 删除模型，释放空间 */
    fun delete() {
        runCatching { modelFile().delete() }
        runCatching { tempFile().delete() }
        _status.value = ModelStatus.NotDownloaded
    }

    /**
     * 开始（或继续）下载。支持多镜像回退与断点续传。
     * 该函数是挂起函数，调用方应在协程中执行。
     */
    suspend fun download() = withContext(Dispatchers.IO) {
        cancelled = false
        val target = modelFile()
        val tmp = tempFile()

        var lastError: String? = null
        for ((index, url) in TranslationModel.MIRRORS.withIndex()) {
            if (cancelled) break
            try {
                downloadFrom(url, index, tmp)
                // 下载完成 -> 校验 -> 原子改名
                val size = tmp.length()
                val expected = TranslationModel.EXPECTED_SIZE
                if (expected > 0 && kotlin.math.abs(size - expected) > expected / 100) {
                    throw IllegalStateException(
                        "下载不完整：得到 " + TranslationModel.humanSize(size) +
                            "，预期 " + TranslationModel.humanSize(expected)
                    )
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("无法重命名下载文件")
                _status.value = ModelStatus.Ready(target.absolutePath, target.length())
                return@withContext
            } catch (e: Exception) {
                if (cancelled) {
                    _status.value = ModelStatus.NotDownloaded
                    return@withContext
                }
                lastError = (e.message ?: e.javaClass.simpleName)
                // 换下一个镜像时保留已下载部分，继续续传
            }
        }
        _status.value = ModelStatus.Failed(
            lastError ?: "所有镜像都不可用，请检查网络后重试"
        )
    }

    private fun downloadFrom(urlStr: String, mirrorIndex: Int, tmp: File) {
        var startAt = if (tmp.exists()) tmp.length() else 0L
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (startAt > 0) setRequestProperty("Range", "bytes=" + startAt + "-")
        }
        conn.connect()

        val code = conn.responseCode
        if (code !in 200..299 && code != 206) {
            throw IllegalStateException("服务器返回 " + code)
        }
        // 服务器不支持续传时从头开始
        if (startAt > 0 && code == 200) {
            tmp.delete()
            startAt = 0L
        }
        val remain = conn.contentLengthLong
        val total = if (remain > 0) remain + startAt else TranslationModel.EXPECTED_SIZE

        RandomAccessFile(tmp, "rw").use { raf ->
            raf.seek(startAt)
            conn.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var got = startAt
                var lastTick = System.currentTimeMillis()
                var baseBytes = startAt
                while (true) {
                    if (cancelled) return
                    val n = input.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    got += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 500) {
                        val secs = maxOf((now - lastTick) / 1000.0, 0.001)
                        val speed = ((got - baseBytes) / secs).toLong()
                        _status.value = ModelStatus.Downloading(got, total, speed, mirrorIndex)
                        lastTick = now
                        baseBytes = got
                    }
                }
                _status.value = ModelStatus.Downloading(got, total, 0, mirrorIndex)
            }
        }
        conn.disconnect()
    }
}
