package com.liuxue.assistant.data.transfer

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * 「面对面快传」的极简 TCP 协议（服务端跑在 Wi-Fi Direct 的 Group Owner 上）。
 *
 * 之所以不用 HTTP：两端都是自家 App，用长度明确的行协议 + 裸字节流更省事，
 * 也避免再引一个 HTTP server 依赖；大文件按 256KB 分块流式读写，不会占内存。
 *
 * 交互（每行 UTF-8，以 \n 结束）：
 *   C: HELLO <验证码>
 *   S: OK | ERR ...
 *   C: LIST
 *   S: FILES <n>
 *      <id>\t<size>\t<mime>\t<base64(urlsafe 文件名)>   × n
 *   C: GET <id>
 *   S: DATA <size>
 *      <size 个字节>
 *   C: PUT <size> <base64(urlsafe 文件名)>
 *   S: READY
 *      <客户端发 size 个字节>
 *   S: DONE <base64(保存后的文件名)>
 *   C: BYE
 */
object TransferProtocol {

    const val PORT = 8988

    /** 读一行；不用 BufferedReader，避免它预读把后面的二进制数据吞掉 */
    fun readLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) throw IllegalStateException("协议头过长")
        }
    }

    fun writeLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun enc(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    fun dec(s: String): String =
        String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)

    /**
     * 精确拷贝 [length] 字节。少于 length 就抛 EOF（说明对端断了），
     * 不会把文件截断成"看起来成功"。
     */
    fun copyExactly(
        input: InputStream,
        output: OutputStream,
        length: Long,
        onProgress: (Long) -> Unit = {}
    ) {
        val buf = ByteArray(256 * 1024)
        var remaining = length
        var done = 0L
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) throw EOFException("传输中断（还差 $remaining 字节）")
            output.write(buf, 0, n)
            remaining -= n
            done += n
            onProgress(done)
        }
        output.flush()
    }
}
