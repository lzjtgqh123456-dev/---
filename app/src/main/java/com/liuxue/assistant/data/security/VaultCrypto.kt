package com.liuxue.assistant.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 证件数据加密。
 *
 * 设计要点：
 * - 密钥由 Android Keystore 生成并保存，私钥材料永不出安全硬件（TEE/StrongBox）。
 * - 算法 AES-256-GCM，每次加密使用随机 IV，密文 = [12 字节 IV][密文+16 字节 tag]。
 * - 文件流式加解密，避免把大图整体读进内存。
 * - 即便手机被 root 或数据库文件被拷走，没有 Keystore 里的密钥也解不开。
 */
object VaultCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vault_master_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val BUFFER = 64 * 1024

    /** 获取（首次自动创建）主密钥 */
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
                // 不要求每次解锁时用户认证；如需更强保护可改为 setUserAuthenticationRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun cipher(mode: Int): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            if (mode == Cipher.ENCRYPT_MODE) {
                init(mode, masterKey())
            }
        }

    // ---------- 内存字节 ----------

    fun encrypt(plain: ByteArray): ByteArray {
        val c = cipher(Cipher.ENCRYPT_MODE)
        val iv = c.iv
        val body = c.doFinal(plain)
        return iv + body
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH) { "密文长度非法" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val body = payload.copyOfRange(IV_LENGTH, payload.size)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        return c.doFinal(body)
    }

    // ---------- 文件流 ----------

    /** 加密写入：输出格式 [IV][ciphertext] */
    fun encryptTo(src: InputStream, dst: OutputStream) {
        val c = cipher(Cipher.ENCRYPT_MODE)
        dst.write(c.iv)
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            c.update(buf, 0, n)?.let { dst.write(it) }
        }
        c.doFinal()?.let { dst.write(it) }
        dst.flush()
    }

    /** 解密读取 */
    fun decryptFrom(src: InputStream, dst: OutputStream) {
        val iv = ByteArray(IV_LENGTH)
        var read = 0
        while (read < IV_LENGTH) {
            val n = src.read(iv, read, IV_LENGTH - read)
            if (n <= 0) error("加密文件已损坏（头部不完整）")
            read += n
        }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))

        val buf = ByteArray(BUFFER)
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            c.update(buf, 0, n)?.let { dst.write(it) }
        }
        c.doFinal()?.let { dst.write(it) }
        dst.flush()
    }

    fun encryptFile(src: File, dst: File) {
        src.inputStream().use { i -> dst.outputStream().use { o -> encryptTo(i, o) } }
    }

    fun decryptFile(src: File, dst: File) {
        src.inputStream().use { i -> dst.outputStream().use { o -> decryptFrom(i, o) } }
    }

    fun decryptFileToBytes(file: File): ByteArray =
        file.inputStream().use { i -> java.io.ByteArrayOutputStream().use { o -> decryptFrom(i, o); o.toByteArray() } }
}
