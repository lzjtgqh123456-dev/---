package com.liuxue.assistant.data.security

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileOutputStream

/**
 * 只读流式 Provider：给系统应用/分享目标用。
 *
 * 关键点：**不把整个文件解密到缓存**，而是解密到一条管道（pipe），
 * 对方读到哪、我们解密到哪 —— 大文件也能几乎立刻打开/分享。
 *
 * URI 形如：content://<包名>.vaultstream/<相对路径>?name=<显示名>&mime=<类型>
 */
class VaultStreamProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: error("no context")
        val rel = uri.pathSegments.joinToString("/")
        require(rel.isNotBlank() && !rel.contains("..")) { "非法路径" }
        val src = VaultStore(ctx).fileFor(rel)
        require(src.exists()) { "文件不存在" }

        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        Thread {
            try {
                FileOutputStream(write.fileDescriptor).use { out ->
                    src.inputStream().use { input -> VaultCrypto.decryptFrom(input, out) }
                }
            } catch (e: Exception) {
                runCatching { write.closeWithError(e.message ?: "解密失败") }
            } finally {
                runCatching { write.close() }
            }
        }.start()
        return read
    }

    override fun getType(uri: Uri): String =
        uri.getQueryParameter("mime")?.takeIf { it.isNotBlank() } ?: "*/*"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val ctx = context ?: error("no context")
        val rel = uri.pathSegments.joinToString("/")
        val src = runCatching { VaultStore(ctx).fileFor(rel) }.getOrNull()
        val name = uri.getQueryParameter("name") ?: src?.name ?: "file"
        // 明文长度按实际格式算（v2 分块 / v1 旧单块）
        val size = src?.let { VaultCrypto.plaintextSize(it) } ?: 0L
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        for (c in cols) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> row.add(name)
                OpenableColumns.SIZE -> row.add(size)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
