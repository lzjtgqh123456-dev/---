package com.liuxue.assistant.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ShareUtils {

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

    /** 用系统应用打开加密文件（流式，大文件也快） */
    fun openStream(context: Context, relativePath: String, displayName: String, mime: String): Boolean {
        val uri = streamUri(context, relativePath, displayName, mime)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, normalizeMime(mime, displayName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = android.content.ClipData.newRawUri(displayName, uri)
        }
        if (runCatching { context.startActivity(view) }.isSuccess) return true
        val chooser = Intent.createChooser(view, "打开").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(chooser) }.isSuccess) return true
        Toast.makeText(context, "没有能打开这个文件的应用，可以试试「分享」到其它 App", Toast.LENGTH_LONG).show()
        return false
    }

    /** 分享加密文件（流式，大文件也快） */
    fun shareStream(context: Context, relativePath: String, displayName: String, mime: String, title: String = "分享") {
        val uri = streamUri(context, relativePath, displayName, mime)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = normalizeMime(mime, displayName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(displayName, uri)
        }
        context.startActivity(
            Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
