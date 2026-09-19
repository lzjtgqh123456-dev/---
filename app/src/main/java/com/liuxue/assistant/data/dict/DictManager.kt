package com.liuxue.assistant.data.dict

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 词典包管理：导入 / 导出 / 删除 / 下载。
 *
 * 需求背景：全量俄汉词典 411 MB，打进 APK 会让安装包过大；而作者不提供服务器。
 * 解决：
 *  - 词典不再内置（assetName = null），APK 只留小的英汉包；
 *  - 用户可以从**任意文件**（微信/USB/网盘）导入，也可以把已装好的词典导出分享给别人；
 *  - 想联网装的话，填一个可下载的直链（如自己的 HuggingFace / 网盘直链）即可，
 *    支持断点续传；本 App 不需要任何自建服务器。
 */
class DictManager(private val context: Context) {

    private val sp = context.getSharedPreferences("dict_manager", Context.MODE_PRIVATE)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 用户手填的地址（可空） */
    fun userUrl(f: DictAssetDb.PackFile): String = sp.getString("url_" + f.key, "").orEmpty()

    fun setUrl(f: DictAssetDb.PackFile, url: String) =
        sp.edit().putString("url_" + f.key, url.trim()).apply()

    /** 默认镜像（来自 DictPackMeta，和离线翻译模型一样：hf-mirror 优先） */
    fun defaultUrls(f: DictAssetDb.PackFile): List<String> = DictPackMeta.mirrorsFor(f.key)

    /** 实际使用顺序：用户手填优先；没填就走默认镜像 */
    fun urls(f: DictAssetDb.PackFile): List<String> {
        val u = userUrl(f).trim()
        return if (u.isNotBlank()) listOf(u) else defaultUrls(f)
    }

    /** 界面预填用 */
    fun url(f: DictAssetDb.PackFile): String = urls(f).firstOrNull().orEmpty()

    fun isInstalled(f: DictAssetDb.PackFile): Boolean = DictAssetDb.isReady(context, f)
    fun size(f: DictAssetDb.PackFile): Long = DictAssetDb.sizeOf(context, f)

    /** 删除词典包，返回释放字节数 */
    fun delete(f: DictAssetDb.PackFile): Long = DictAssetDb.deleteFile(context, f)

    /** 从本地文件（SAF uri）导入；校验是 SQLite 文件，避免导入错文件 */
    fun importFrom(f: DictAssetDb.PackFile, uri: Uri): Long {
        val dest = DictAssetDb.fileFor(context, f)
        DictAssetDb.closeFile(f)
        val tmp = File(dest.parentFile, dest.name + ".importing")
        tmp.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法读取所选文件")
        check(isSqlite(tmp)) { "所选文件不是有效的词典库（SQLite）" }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return dest.length()
    }

    /** 导出词典包到用户选择的位置（方便分享给别人的手机） */
    fun exportTo(f: DictAssetDb.PackFile, uri: Uri): Long {
        val src = DictAssetDb.fileFor(context, f)
        check(src.exists()) { "词典未安装" }
        context.contentResolver.openOutputStream(uri)?.use { output ->
            src.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("无法写入所选位置")
        return src.length()
    }

    /**
     * 下载词典包：用户手填地址优先，否则按默认镜像依次尝试（失败自动换源）。
     * 支持断点续传；落 .part，校验 SQLite 头后原子改名。
     */
    suspend fun download(
        f: DictAssetDb.PackFile,
        onProgress: (downloaded: Long, total: Long, mirrorIndex: Int) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val candidates = urls(f)
        require(candidates.isNotEmpty()) { "没有可用的下载地址：请在「词典管理」里填入直链（或在 DictPackMeta 配置默认源）" }
        var lastError: Throwable? = null
        for ((index, u) in candidates.withIndex()) {
            val r = runCatching { downloadFrom(f, u) { d, t -> onProgress(d, t, index) } }
            if (r.isSuccess) {
                val size = r.getOrThrow()
                val exp = if (f.key == "ru") DictPackMeta.RU_EXPECTED_SIZE else 0L
                if (exp > 0 && kotlin.math.abs(size - exp) > exp / 100) {
                    throw IOException("下载不完整（" + size + "/" + exp + " 字节），请重试续传")
                }
                return@withContext size
            }
            lastError = r.exceptionOrNull()
        }
        throw lastError ?: IOException("下载失败")
    }

    /** 从单个地址下载（阻塞式，需在 IO 线程调用） */
    private fun downloadFrom(
        f: DictAssetDb.PackFile,
        url: String,
        onProgress: (Long, Long) -> Unit
    ): Long {
        val dest = DictAssetDb.fileFor(context, f)
        DictAssetDb.closeFile(f)
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.parentFile?.mkdirs()
        var existing = if (tmp.exists()) tmp.length() else 0L
        val builder = Request.Builder().url(url)
        if (existing > 0) builder.addHeader("Range", "bytes=$existing-")
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code)
            val append = resp.code == 206
            if (!append) existing = 0L
            val body = resp.body ?: throw IOException("响应为空")
            val total = if (body.contentLength() > 0) body.contentLength() + existing else -1L
            var done = existing
            body.byteStream().use { input ->
                FileOutputStream(tmp, append).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        check(tmp.length() > 1024 && isSqlite(tmp)) { "下载的文件不是有效的词典库" }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return dest.length()
    }

    private fun isSqlite(file: File): Boolean {
        if (!file.exists() || file.length() < 100) return false
        val header = ByteArray(16)
        return runCatching {
            file.inputStream().use { it.read(header) }
            String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
        }.getOrDefault(false)
    }
}
