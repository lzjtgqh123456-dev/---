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
        // 命中缓存（大小对得上）就直接复用：同一份文件第二次打开 / 播放就不用再解密一遍
        if (out.isFile && out.length() > 0 && out.length() == VaultCrypto.plaintextSize(f)) {
            return out
        }
        val ok = runCatching {
            out.outputStream().use { o -> f.inputStream().use { i -> VaultCrypto.decryptFrom(i, o) } }
        }.isSuccess
        if (!ok) {
            out.delete()
            return null
        }
        // 老格式（v1/v2）走的是 Keystore 慢路径 —— 读完顺手升级成 v3，
        // 下次打开就不用再等那一两分钟了。先写临时文件再改名，失败不动原文件。
        if (VaultCrypto.needsUpgrade(f)) {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".up")
                tmp.outputStream().use { o -> out.inputStream().use { i -> VaultCrypto.encryptTo(i, o) } }
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }
        return out
    }

    /**
     * 还没升级成 v3 的文件（不含 .dek）。用来在启动时提示用户"一键升级"，
     * 免得第一次打开大文件还要等一两分钟。
     */
    fun legacyFiles(): List<File> = runCatching {
        (root.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.startsWith(".") && VaultCrypto.needsUpgrade(it) }
    }.getOrDefault(emptyList())

    /**
     * 把一个旧格式（v1/v2）文件原地升级成 v3。
     * 先解密到 cache，再用 v3 写临时文件，最后改名覆盖 —— 任何一步失败都不动原文件。
     */
    fun upgradeLegacyFile(file: File): Boolean {
        if (!file.isFile || !VaultCrypto.needsUpgrade(file)) return true
        val plain = File(cacheDir, "up_" + file.name + ".plain")
        val enc = File(file.parentFile, file.name + ".up")
        return try {
            plain.outputStream().use { o ->
                file.inputStream().use { i -> VaultCrypto.decryptFrom(i, o) }
            }
            enc.outputStream().use { o ->
                plain.inputStream().use { i -> VaultCrypto.encryptTo(i, o) }
            }
            if (!enc.renameTo(file)) error("改名失败")
            true
        } catch (e: Exception) {
            enc.delete()
            false
        } finally {
            plain.delete()
        }
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
