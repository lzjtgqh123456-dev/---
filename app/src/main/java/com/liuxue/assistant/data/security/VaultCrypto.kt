package com.liuxue.assistant.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 证件 / 资料数据加密。
 *
 * ## 密钥设计：信封加密（envelope encryption）
 * - **主密钥 KEK**：Android Keystore 里的 AES-256-GCM 密钥，密钥材料永不出 TEE，别名 [KEY_ALIAS]。
 * - **数据密钥 DEK**：随机 32 字节，首次使用时生成一次，用 KEK 包起来存到
 *   `filesDir/vault/.dek`（没有 KEK 就解不开）。
 * - **文件正文用 DEK + 软件 AES-GCM**（Android 默认 provider，走 AES-NI 硬件指令）。
 *
 * ## 为什么不直接用 Keystore 加解密文件（两个坑，别再改回去）
 * 1. **慢**：Keystore 每个 cipher 操作都要过一遍 keystore 守护进程，实测约 2.5 MB/s，
 *    445MB 视频要 3 分钟；软件 AES 是几百 MB/s，快两个数量级。
 * 2. **假流式**：Keystore 的 GCM 内部是 BufferAllOutputUntilDoFinalStreamer，
 *    update() 的输出全攒在内存里直到 doFinal()，解密 134MB 会直接 OOM 崩掉。
 *
 * ## 文件格式
 * | 版本 | 结构 | 状态 |
 * |---|---|---|
 * | v1 | [12B IV][密文][16B tag]（Keystore 单块） | 只读兼容 |
 * | v2 | "VLTC2" + 分块 [12B IV][密文][16B tag]（Keystore，块 4MB） | 只读兼容 |
 * | v3 | "VLTC3" + [12B IV][密文][16B tag]（DEK + 软件 AES-GCM，真流式） | 新文件一律用它 |
 *
 * 安全性等价：拷走 DB/文件但拿不到 Keystore 的 KEK，就解不开 .dek，也就解不开任何正文。
 */
object VaultCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vault_master_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val TAG_LENGTH = TAG_BITS / 8
    private const val BUFFER = 256 * 1024
    /** 旧格式（Keystore）每次 update 都是一趟 keystore 守护进程往返，块太小会非常慢 */
    private const val LEGACY_BUFFER = 8 * 1024 * 1024

    private const val DEK_FILE = ".dek"
    private const val DEK_BYTES = 32

    /** v2 分块大小（只影响读老文件） */
    private const val CHUNK = 4 * 1024 * 1024

    private const val MAGIC_LEN = 6

    private val MAGIC_V2 = byteArrayOf(
        'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(),
        'C'.code.toByte(), '2'.code.toByte(), 0
    )
    private val MAGIC_V3 = byteArrayOf(
        'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(),
        'C'.code.toByte(), '3'.code.toByte(), 0
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var dekCache: SecretKey? = null

    /** 必须在 Application.onCreate 里调用一次（DEK 需要落盘位置） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---------- 密钥 ----------

    /** Keystore 主密钥（KEK）：首次自动创建 */
    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun dekFile(): File {
        val ctx = appContext
            ?: error("VaultCrypto 未初始化：请在 Application.onCreate 里调用 VaultCrypto.init(this)")
        val dir = File(ctx.filesDir, "vault").apply { if (!exists()) mkdirs() }
        return File(dir, DEK_FILE)
    }

    /**
     * 数据密钥（DEK）：随机 32 字节，用 KEK 包起来存 .dek，只在第一次生成。
     * 有 .dek 就必须能解开 —— 解不开说明密钥环境变了，绝不能偷偷换新的，
     * 否则已有密文会全部变成永远读不出来的数据。
     */
    private fun dataKey(): SecretKey {
        dekCache?.let { return it }
        synchronized(this) {
            dekCache?.let { return it }
            val f = dekFile()
            val raw = if (f.isFile && f.length() > 0) {
                unwrapDek(f.readBytes())
            } else {
                ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
                    .also { f.writeBytes(wrapDek(it)) }
            }
            return SecretKeySpec(raw, "AES").also { dekCache = it }
        }
    }

    private fun wrapDek(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        return c.iv + c.doFinal(raw)
    }

    private fun unwrapDek(payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH + TAG_LENGTH) { "数据密钥文件已损坏" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val body = payload.copyOfRange(IV_LENGTH, payload.size)
        val c = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        }
        return c.doFinal(body)
    }

    private fun softEncryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, dataKey()) }

    private fun softDecryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(TAG_BITS, iv))
        }

    // ---------- 内存字节 ----------

    fun encrypt(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plain.size + 64)
        ByteArrayInputStream(plain).use { encryptTo(it, out) }
        return out.toByteArray()
    }

    fun decrypt(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size.coerceAtLeast(64))
        ByteArrayInputStream(payload).use { decryptFrom(it, out) }
        return out.toByteArray()
    }

    // ---------- 文件流 ----------

    /**
     * 加密写入（v3：DEK + 软件 AES-GCM，**按 4MB 分块**）。
     *
     * 为什么还要分块：即使是软件 provider，Android 的 `Cipher.update()` 对 GCM 这种 AEAD
     * 模式**依然会把输出攒到 doFinal**（实测 445MB 会申请 536MB 的 ByteArrayOutputStream → OOM）。
     * 分块后每块独立 doFinal，内存峰值 = 一块（约 4MB），而每次 init 用的是原始密钥
     * （不走 keystore 守护进程），所以依然很快。
     */
    fun encryptTo(src: InputStream, dst: OutputStream) {
        dst.write(MAGIC_V3)
        val buf = ByteArray(CHUNK)
        while (true) {
            val n = readFully(src, buf)
            if (n <= 0) break
            val c = softEncryptCipher()
            dst.write(c.iv)
            dst.write(c.doFinal(buf, 0, n))
        }
        dst.flush()
    }

    /** 解密读取：自动识别 v1 / v2 / v3 */
    fun decryptFrom(src: InputStream, dst: OutputStream) {
        val head = ByteArray(MAGIC_LEN)
        val got = readUpTo(src, head, MAGIC_LEN)
        when {
            got == MAGIC_LEN && head.contentEquals(MAGIC_V3) -> decryptV3(src, dst)
            got == MAGIC_LEN && head.contentEquals(MAGIC_V2) -> decryptChunkedBody(src, dst)
            else -> decryptLegacyBody(head, got, src, dst)
        }
        dst.flush()
    }

    /** v3：DEK + 软件 AES-GCM，按块解密（内存峰值 = 一块） */
    private fun decryptV3(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(CHUNK + TAG_LENGTH)
        while (true) {
            val iv = ByteArray(IV_LENGTH)
            val ivGot = readUpTo(src, iv, IV_LENGTH)
            if (ivGot == 0) break
            if (ivGot < IV_LENGTH) error("加密文件已损坏（v3 分块头部不完整）")
            val n = readUpTo(src, buf, buf.size)
            if (n < TAG_LENGTH) error("加密文件已损坏（v3 分块数据不完整）")
            dst.write(softDecryptCipher(iv).doFinal(buf, 0, n))
        }
    }

    /** v2：Keystore 分块（老文件，慢但可用） */
    private fun decryptChunkedBody(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(CHUNK + TAG_LENGTH)
        while (true) {
            val iv = ByteArray(IV_LENGTH)
            val ivGot = readUpTo(src, iv, IV_LENGTH)
            if (ivGot == 0) break
            if (ivGot < IV_LENGTH) error("加密文件已损坏（分块头部不完整）")
            val n = readUpTo(src, buf, buf.size)
            if (n < TAG_LENGTH) error("加密文件已损坏（分块数据不完整）")
            val c = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            dst.write(c.doFinal(buf, 0, n))
        }
    }

    /**
     * v1：Keystore 单块（最老的文件，靠 largeHeap 兜内存）。
     * headLen 只是"探测格式时已读到的字节数"（最多 MAGIC_LEN 个），是 IV 的前半段，
     * **不是**完整 IV —— 别拿它跟 IV_LENGTH 比较（这里踩过坑：会让所有旧文件都解不开）。
     */
    private fun decryptLegacyBody(
        head: ByteArray,
        headLen: Int,
        src: InputStream,
        dst: OutputStream
    ) {
        if (headLen <= 0) error("加密文件已损坏（头部不完整）")
        val iv = ByteArray(IV_LENGTH)
        val copied = minOf(headLen, IV_LENGTH)
        System.arraycopy(head, 0, iv, 0, copied)
        var filled = copied
        while (filled < IV_LENGTH) {
            val n = src.read(iv, filled, IV_LENGTH - filled)
            if (n <= 0) error("加密文件已损坏（头部不完整）")
            filled += n
        }
        val c = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        }
        // 大块喂：块越大越省 keystore 往返（8MB 时内存峰值约 24MB，可接受）
        val buf = ByteArray(LEGACY_BUFFER)
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            c.update(buf, 0, n)?.let { if (it.isNotEmpty()) dst.write(it) }
        }
        c.doFinal()?.let { dst.write(it) }
    }

    fun encryptFile(src: File, dst: File) {
        src.inputStream().use { i -> dst.outputStream().use { o -> encryptTo(i, o) } }
    }

    fun decryptFile(src: File, dst: File) {
        src.inputStream().use { i -> dst.outputStream().use { o -> decryptFrom(i, o) } }
    }

    fun decryptFileToBytes(file: File): ByteArray =
        file.inputStream().use { i ->
            ByteArrayOutputStream(file.length().toInt().coerceAtLeast(64)).use { o ->
                decryptFrom(i, o)
                o.toByteArray()
            }
        }

    /** 是不是老格式（v1 / v2）—— 老格式要走 Keystore 慢路径，值得升级 */
    fun needsUpgrade(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(MAGIC_LEN)
            val got = readUpTo(input, head, MAGIC_LEN)
            !(got == MAGIC_LEN && head.contentEquals(MAGIC_V3))
        }
    }.getOrDefault(false)

    /**
     * 反推明文长度（给 Provider 报 SIZE 用，不真的解密）。
     * v1/v3 = 总长 - 28（v3 再减 6 字节魔数）；v2 每块 28 字节开销。
     */
    fun plaintextSize(file: File): Long {
        val total = file.length()
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(MAGIC_LEN)
                val got = readUpTo(input, head, MAGIC_LEN)
                val overhead = (IV_LENGTH + TAG_LENGTH).toLong()
                when {
                    got == MAGIC_LEN && (head.contentEquals(MAGIC_V3) || head.contentEquals(MAGIC_V2)) -> {
                        var remain = total - got
                        var plain = 0L
                        while (remain >= TAG_LENGTH) {
                            val ct = if (remain >= CHUNK + overhead) CHUNK.toLong() else remain - overhead
                            if (ct <= 0) break
                            plain += ct
                            remain -= ct + overhead
                        }
                        plain
                    }

                    else -> (total - overhead).coerceAtLeast(0L)
                }
            }
        }.getOrDefault(0L)
    }

    // ---------- 小工具 ----------

    /** 尽量读满 buf，返回实际读到的字节数（0 = 已到结尾） */
    private fun readFully(src: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = src.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    /** 最多读 len 个字节，返回实际读到的字节数 */
    private fun readUpTo(src: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = src.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        return off
    }
}
