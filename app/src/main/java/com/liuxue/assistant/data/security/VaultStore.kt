package com.liuxue.assistant.data.security

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 加密文件仓库：所有证件图片/附件都加密落在 filesDir/vault 下。
 * 对外只暴露"相对路径"，绝不落明文到磁盘。
 */
class VaultStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "vault").apply { if (!exists()) mkdirs() }

    private val cacheDir: File
        get() = File(context.cacheDir, "vault_preview").apply { if (!exists()) mkdirs() }

    /** 加密文件本体（给流式 Provider 用，不要外泄路径） */
    fun fileFor(relativePath: String): File = File(root, relativePath)

    /** 加密保存字节，返回相对路径 */
    fun saveBytes(bytes: ByteArray, ext: String = "bin"): String {
        val name = "${UUID.randomUUID()}.$ext"
        val target = File(root, name)
        VaultCrypto.encrypt(bytes).let { target.writeBytes(it) }
        return name
    }

    /** 加密保存来源流（导入图片/文件用），返回相对路径 */
    fun saveStream(input: java.io.InputStream, ext: String): String {
        val name = "${UUID.randomUUID()}.$ext"
        val target = File(root, name)
        target.outputStream().use { out -> VaultCrypto.encryptTo(input, out) }
        return name
    }

    /** 读取并解密为内存字节 */
    fun readBytes(relativePath: String): ByteArray? {
        val f = File(root, relativePath)
        if (!f.exists()) return null
        return runCatching { VaultCrypto.decryptFileToBytes(f) }.getOrNull()
    }

    /**
     * 解密到缓存目录，返回明文 File（用于系统看图/分享/WebView 等需要真实文件的场景）。
     * 调用方应在用完后清理。文件名做了混淆，避免泄露原始名称。
     */
    fun materialize(relativePath: String, ext: String = "jpg"): File? {
        val f = File(root, relativePath)
        if (!f.exists()) return null
        val out = File(cacheDir, "${relativePath.hashCode().toUInt()}.$ext")
        return runCatching {
            out.outputStream().use { o -> f.inputStream().use { i -> VaultCrypto.decryptFrom(i, o) } }
            out
        }.getOrNull()
    }

    /** 删除加密文件 */
    fun delete(relativePath: String): Boolean = File(root, relativePath).delete()

    fun exists(relativePath: String): Boolean = File(root, relativePath).exists()

    /** 全库体积（字节） */
    fun totalSize(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    /** 清空预览缓存 */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
