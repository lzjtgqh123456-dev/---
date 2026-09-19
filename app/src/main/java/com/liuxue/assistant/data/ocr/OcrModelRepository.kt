package com.liuxue.assistant.data.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

/** OCR 模型整体状态 */
sealed interface OcrModelsStatus {
    /** 还缺文件（[missing] 为缺的逻辑 key） */
    data class Missing(
        val missing: List<String>,
        val haveBytes: Long,
        val needBytes: Long
    ) : OcrModelsStatus

    /** 正在下载 */
    data class Downloading(
        val label: String,
        val fileIndex: Int,
        val fileCount: Int,
        val fileDone: Long,
        val fileTotal: Long,
        val bytesPerSec: Long,
        val overallDone: Long,
        val overallTotal: Long
    ) : OcrModelsStatus {
        val percent: Int
            get() = if (overallTotal <= 0) 0
            else ((overallDone * 100) / overallTotal).toInt().coerceIn(0, 100)
    }

    /** 全部就绪 */
    data class Ready(val bytes: Long) : OcrModelsStatus

    /** 出错 */
    data class Failed(val message: String) : OcrModelsStatus
}

/**
 * OCR 模型（det / rec / keys）的下载与校验。
 *
 * 复刻 `data/translate/ModelRepository.kt` 的可靠性设计：
 * 多镜像回退、Range 断点续传、`.part` 原子改名、进度 Flow、大小校验。
 * 区别只是这里要下载**一组**文件（3~6 个），所以按文件串行下载并汇报总进度。
 */
class OcrModelRepository(private val context: Context) {

    private val _status = MutableStateFlow<OcrModelsStatus>(
        OcrModelsStatus.Missing(OcrModels.BATCH1.map { it.key }, 0L, 0L)
    )
    val status: StateFlow<OcrModelsStatus> = _status.asStateFlow()

    @Volatile private var cancelled = false

    /** 模型目录：应用外部私有目录，卸载即清理，不需要存储权限；路径保持纯 ASCII */
    fun dir(): File = File(context.getExternalFilesDir(null), "ocr").apply { if (!exists()) mkdirs() }

    fun file(spec: OcrModels.Spec): File = File(dir(), spec.fileName)

    fun path(spec: OcrModels.Spec): String = file(spec).absolutePath

    private fun tempFile(spec: OcrModels.Spec): File = File(dir(), spec.fileName + ".part")

    /**
     * 单个文件是否已就绪（存在且大小可接受）。
     * 大小检查很便宜，每次刷新都做；SHA-256 只在**下载完成时**校验一次（否则每次进页面都要读 25 MB）。
     */
    fun isReady(spec: OcrModels.Spec): Boolean {
        val f = file(spec)
        return f.isFile && spec.acceptsSize(f.length())
    }

    /** 第一批（中英）是否全部就绪 */
    fun batch1Ready(): Boolean = OcrModels.BATCH1.all { isReady(it) }

    /** 已落盘总体积 */
    fun presentBytes(): Long = OcrModels.ALL.filter { file(it).isFile }.sumOf { file(it).length() }

    /** 已下载体积（含未完成的 .part），用于显示"继续下载" */
    fun downloadedBytes(specs: List<OcrModels.Spec>): Long =
        specs.sumOf { maxOf(file(it).length(), tempFile(it).length()) }

    fun refresh(specs: List<OcrModels.Spec> = OcrModels.BATCH1) {
        val missing = specs.filterNot { isReady(it) }
        _status.value = if (missing.isEmpty()) {
            OcrModelsStatus.Ready(presentBytes())
        } else {
            OcrModelsStatus.Missing(
                missing = missing.map { it.key },
                haveBytes = specs.filter { isReady(it) }.sumOf { file(it).length() },
                needBytes = specs.sumOf { it.expectedSize }
            )
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun deleteAll() {
        OcrModels.ALL.forEach {
            runCatching { file(it).delete() }
            runCatching { tempFile(it).delete() }
        }
        refresh(OcrModels.ALL)
    }

    /** 删除单个文件（用于"某个文件坏了重新下"） */
    fun delete(spec: OcrModels.Spec) {
        runCatching { file(spec).delete() }
        runCatching { tempFile(spec).delete() }
    }

    /**
     * 下载缺失文件（挂起，需在协程中调用）。
     * 单个文件失败不影响其它文件：最后统一汇总为 Failed。
     */
    suspend fun download(specs: List<OcrModels.Spec> = OcrModels.BATCH1) = withContext(Dispatchers.IO) {
        cancelled = false
        val todo = specs.filterNot { isReady(it) }
        if (todo.isEmpty()) {
            refresh(specs)
            return@withContext
        }
        val overallTotal = todo.sumOf { it.expectedSize }
        var overallDone = todo.sumOf { maxOf(file(it).length(), tempFile(it).length()) }
        val errors = mutableListOf<String>()

        todo.forEachIndexed { index, spec ->
            if (cancelled) return@withContext
            var ok = false
            var lastError: String? = null
            for ((mirrorIndex, mirror) in spec.mirrors.withIndex()) {
                if (cancelled) return@withContext
                try {
                    downloadOne(spec, mirror, mirrorIndex, index, todo.size, overallDone, overallTotal)
                    val tmp = tempFile(spec)
                    if (!spec.acceptsSize(tmp.length())) {
                        throw IllegalStateException(
                            "下载不完整：得到 " + OcrModels.humanSize(tmp.length()) +
                                "，预期 " + OcrModels.humanSize(mirror.size)
                        )
                    }
                    val sha = sha256(tmp)
                    if (!spec.acceptsSha(sha)) {
                        throw IllegalStateException("文件校验不过（SHA-256 不匹配），已丢弃重下")
                    }
                    val target = file(spec)
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) throw IllegalStateException("无法重命名下载文件")
                    overallDone += target.length()
                    ok = true
                    break
                } catch (e: Exception) {
                    if (cancelled) return@withContext
                    lastError = e.message ?: e.javaClass.simpleName
                    // 保留已下载部分，换下一个镜像继续续传
                }
            }
            if (!ok) {
                errors += spec.label + "：" + (lastError ?: "所有镜像都不可用")
                // 该文件失败后清掉临时文件，避免下次带着坏数据续传
                runCatching { tempFile(spec).delete() }
            }
        }

        if (cancelled) return@withContext
        if (errors.isEmpty()) {
            refresh(specs)
        } else {
            _status.value = OcrModelsStatus.Failed(errors.joinToString("；"))
        }
    }

    /** 流式计算 SHA-256（小写十六进制） */
    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadOne(
        spec: OcrModels.Spec,
        mirror: OcrModels.Mirror,
        mirrorIndex: Int,
        fileIndex: Int,
        fileCount: Int,
        overallDone0: Long,
        overallTotal: Long
    ) {
        val tmp = tempFile(spec)
        var startAt = if (tmp.exists()) tmp.length() else 0L
        // 上次换镜像可能留下比目标更大的垃圾，直接重来
        if (mirror.size > 0 && startAt > mirror.size) {
            tmp.delete(); startAt = 0L
        }
        val conn = (URL(mirror.url).openConnection() as HttpURLConnection).apply {
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
        if (startAt > 0 && code == 200) {
            tmp.delete()
            startAt = 0L
        }
        val remain = conn.contentLengthLong
        val total = if (remain > 0) remain + startAt else mirror.size

        RandomAccessFile(tmp, "rw").use { raf ->
            raf.seek(startAt)
            conn.inputStream.use { input ->
                val buf = ByteArray(128 * 1024)
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
                    if (now - lastTick >= 400) {
                        val secs = maxOf((now - lastTick) / 1000.0, 0.001)
                        val speed = ((got - baseBytes) / secs).toLong()
                        _status.value = OcrModelsStatus.Downloading(
                            label = spec.label,
                            fileIndex = fileIndex,
                            fileCount = fileCount,
                            fileDone = got,
                            fileTotal = total,
                            bytesPerSec = speed,
                            overallDone = overallDone0 + (got - startAt),
                            overallTotal = overallTotal
                        )
                        lastTick = now
                        baseBytes = got
                    }
                }
                _status.value = OcrModelsStatus.Downloading(
                    label = spec.label,
                    fileIndex = fileIndex,
                    fileCount = fileCount,
                    fileDone = got,
                    fileTotal = total,
                    bytesPerSec = 0,
                    overallDone = overallDone0 + (got - startAt),
                    overallTotal = overallTotal
                )
            }
        }
        conn.disconnect()
    }
}
