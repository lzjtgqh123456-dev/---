package com.liuxue.assistant.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ShareUtils {

    private val main = Handler(Looper.getMainLooper())

    fun shareFile(context: Context, file: File, mime: String, title: String = "分享") {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 流式 URI：解密在 Provider 里按需进行，不落整份明文 */
    fun streamUri(context: Context, relativePath: String, displayName: String, mime: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.vaultstream")
            .appendPath(relativePath)
            .appendQueryParameter("name", displayName)
            .appendQueryParameter("mime", normalizeMime(mime, displayName))
            .build()

    /**
     * 用系统应用打开加密文件。
     *
     * ⚠️ 不要直接把「管道 URI」交给系统 App：pipe **不可 seek**，PDF/Office/视频播放器
     * 一 seek 就失败（表现就是「卡住很久，然后报错/提示文件损坏」），大文件尤其明显。
     * 所以这里先把密文解密成 cache 里的普通文件（可 seek），再走 FileProvider 打开。
     * 解密放在后台线程做，先给个「正在准备文件…」的提示，不卡界面。
     */
    fun openStream(context: Context, relativePath: String, displayName: String, mime: String): Boolean {
        materializeAsync(context, relativePath, displayName) { file ->
            if (file == null) {
                Toast.makeText(context, "解密失败，无法打开这个文件", Toast.LENGTH_LONG).show()
            } else {
                openFile(context, file, mime, "打开")
            }
        }
        return true
    }

    /** 分享加密文件（同样先解密到 cache，避免对方 App 读不可 seek 的流） */
    fun shareStream(
        context: Context,
        relativePath: String,
        displayName: String,
        mime: String,
        title: String = "分享"
    ) {
        materializeAsync(context, relativePath, displayName) { file ->
            if (file == null) {
                Toast.makeText(context, "解密失败，无法分享这个文件", Toast.LENGTH_LONG).show()
            } else {
                shareFile(context, file, normalizeMime(mime, displayName), title)
            }
        }
    }

    /**
     * 后台把加密文件解密成 cache/vault_preview 下的普通文件（保留原名后缀，方便系统按类型选 App）。
     * 完成后回主线程回调；失败给 null。
     */
    private fun materializeAsync(
        context: Context,
        relativePath: String,
        displayName: String,
        onReady: (File?) -> Unit
    ) {
        Toast.makeText(context, "正在准备文件…", Toast.LENGTH_SHORT).show()
        Thread {
            val f = runCatching {
                val store = com.liuxue.assistant.data.security.VaultStore(context)
                // 文件名里的路径分隔符要清掉，避免写到别的目录
                val safe = displayName.ifBlank { "file" }
                    .replace(Regex("""[\\/]"""), "_").take(80)
                val ext = safe.substringAfterLast('.', "bin").take(8)
                store.materialize(relativePath, ext)?.let { plain ->
                    val named = File(plain.parentFile, safe)
                    named.delete()                       // 覆盖上一次的同名缓存
                    if (plain.renameTo(named)) named else plain
                }
            }.getOrNull()
            main.post { runCatching { onReady(f) } }
        }.start()
    }

    /**
     * 用系统默认应用打开文件（PDF/Word/Excel/视频…）。
     * 1) 先直接 ACTION_VIEW（比 chooser 更容易命中能打开的 App）；
     * 2) 失败再走分享面板；
     * 3) MIME 是 application/octet-stream 时按扩展名猜；
     * 4) 都失败给个明确的提示，不再「点了没反应」。
     */
    fun openFile(context: Context, file: File, mime: String, title: String = "打开"): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: run {
            Toast.makeText(context, "文件准备失败", Toast.LENGTH_SHORT).show()
            return false
        }
        val realMime = normalizeMime(mime, file.name)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, realMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = android.content.ClipData.newRawUri(null, uri)
        }
        if (runCatching { context.startActivity(view); }.isSuccess) return true
        val chooser = Intent.createChooser(view, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(chooser); }.isSuccess) return true
        Toast.makeText(context, "没有能打开这个文件的应用，可以试试「分享」到其它 App", Toast.LENGTH_LONG).show()
        return false
    }

    /** 有些选择器给的是 application/octet-stream，按扩展名纠正 */
    fun normalizeMime(mime: String, name: String): String {
        val m = mime.trim().lowercase()
        if (m.isNotBlank() && m != "application/octet-stream" && m != "*/*") return m
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt", "md", "log" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }

    /** 分享纯文本（作业内容、词条等） */
    fun shareText(context: Context, text: String, title: String = "分享") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
