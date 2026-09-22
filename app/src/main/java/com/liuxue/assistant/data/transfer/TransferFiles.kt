package com.liuxue.assistant.data.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream

/** 文件名处理 + 接收文件落盘（优先系统「下载」目录，不需要权限） */
object TransferFiles {

    /** 去掉路径分隔符等危险字符，只留文件名本体 */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim('.', ' ')
        return cleaned.ifBlank { "received.bin" }.take(180)
    }

    /** 目录里找不冲突的文件名：a.jpg -> a (1).jpg */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$stem ($i)$ext")
            i++
        }
        return f
    }

    fun guessMime(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file.bin"
    }

    fun sizeOf(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) return c.getLong(i)
            }
        }
        return -1L
    }

    /**
     * 把 [input] 的 [size] 个字节存到系统「下载/留学助手快传」。
     * API 29+ 走 MediaStore（无需存储权限）；更老的版本走公共目录（需 WRITE_EXTERNAL_STORAGE）。
     * 返回最终保存的文件名。
     */
    fun saveToDownloads(
        context: Context,
        name: String,
        size: Long,
        input: InputStream,
        onProgress: (Long) -> Unit = {}
    ): String {
        val safe = sanitize(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, guessMime(safe))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/留学助手快传")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在「下载」目录创建文件")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    TransferProtocol.copyExactly(input, out, size, onProgress)
                } ?: error("无法写入「下载」目录")
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return safe
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "留学助手快传"
        ).apply { mkdirs() }
        val target = uniqueFile(dir, safe)
        target.outputStream().use { out ->
            TransferProtocol.copyExactly(input, out, size, onProgress)
        }
        return target.name
    }
}
