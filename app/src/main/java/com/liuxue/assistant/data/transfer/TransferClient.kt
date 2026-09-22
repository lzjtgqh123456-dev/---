package com.liuxue.assistant.data.transfer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** 要发给对方的文件（SAF uri，需要时才打开流） */
data class OutgoingFile(
    val name: String,
    val size: Long,
    val mime: String,
    val open: () -> InputStream?
)

/** 对方列表里的文件 */
data class RemoteFile(
    val id: Int,
    val name: String,
    val size: Long,
    val mime: String
)

/**
 * 加入方（Group Owner 之外的这一端）：
 * 连到 GO 的 TCP 服务，先把对方的文件拉下来，再把自己的文件推上去 —— 一次连接完成双向互传。
 */
class TransferClient(
    private val context: Context,
    private val host: String,
    private val token: String,
    private val onLog: (String) -> Unit,
    private val onProgress: (name: String, direction: String, done: Long, total: Long) -> Unit,
    /** 自测用：指定接收目录；null = 系统「下载/留学助手快传」 */
    private val saveDir: java.io.File? = null
) {

    /** @return Pair(收到的文件名, 发出的文件名) */
    suspend fun exchange(localFiles: List<OutgoingFile>): Pair<List<String>, List<String>> =
        withContext(Dispatchers.IO) {
            val received = ArrayList<String>()
            val sent = ArrayList<String>()
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, TransferProtocol.PORT), 10_000)
                socket.soTimeout = 60_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                TransferProtocol.writeLine(output, "HELLO $token")
                val hello = TransferProtocol.readLine(input)
                if (hello != "OK") throw IllegalStateException(hello ?: "对方没有响应")
                onLog("已连接到对方，开始互传…")

                // 1) 拉取对方的文件
                TransferProtocol.writeLine(output, "LIST")
                val header = TransferProtocol.readLine(input) ?: throw IllegalStateException("读取列表失败")
                val n = header.removePrefix("FILES ").trim().toIntOrNull()
                    ?: throw IllegalStateException("列表格式错误：$header")
                val remote = ArrayList<RemoteFile>(n)
                repeat(n) {
                    val line = TransferProtocol.readLine(input) ?: throw IllegalStateException("列表不完整")
                    val p = line.split("\t")
                    if (p.size >= 4) {
                        remote += RemoteFile(
                            id = p[0].toIntOrNull() ?: 0,
                            size = p[1].toLongOrNull() ?: 0L,
                            mime = p[2],
                            name = runCatching { TransferProtocol.dec(p[3]) }.getOrDefault("file.bin")
                        )
                    }
                }
                remote.forEach { f ->
                    TransferProtocol.writeLine(output, "GET ${f.id}")
                    val data = TransferProtocol.readLine(input) ?: throw IllegalStateException("下载失败")
                    if (data.startsWith("ERR")) {
                        onLog("跳过「${f.name}」：$data")
                        return@forEach
                    }
                    val size = data.removePrefix("DATA ").trim().toLongOrNull()
                        ?: throw IllegalStateException("下载格式错误：$data")
                    val saved = if (saveDir != null) {
                        val target = TransferFiles.uniqueFile(saveDir, TransferFiles.sanitize(f.name))
                        target.outputStream().use { out ->
                            TransferProtocol.copyExactly(input, out, size) { done ->
                                onProgress(f.name, "接收", done, size)
                            }
                        }
                        target.name
                    } else {
                        TransferFiles.saveToDownloads(context, f.name, size, input) { done ->
                            onProgress(f.name, "接收", done, size)
                        }
                    }
                    received += saved
                    onLog("已接收：$saved")
                }

                // 2) 推送本机文件
                localFiles.forEach { f ->
                    val stream = f.open()
                    if (stream == null) {
                        onLog("跳过「${f.name}」：读不到文件")
                        return@forEach
                    }
                    TransferProtocol.writeLine(
                        output,
                        "PUT ${f.size} ${TransferProtocol.enc(f.name)}"
                    )
                    val ready = TransferProtocol.readLine(input)
                    if (ready != "READY") {
                        stream.close()
                        throw IllegalStateException(ready ?: "对方拒绝接收")
                    }
                    stream.use {
                        TransferProtocol.copyExactly(it, output, f.size) { done ->
                            onProgress(f.name, "发送", done, f.size)
                        }
                    }
                    val done = TransferProtocol.readLine(input) ?: throw IllegalStateException("发送未确认")
                    if (done.startsWith("DONE")) {
                        sent += f.name
                        onLog("已发送：${f.name}")
                    } else {
                        onLog("发送「${f.name}」失败：$done")
                    }
                }

                TransferProtocol.writeLine(output, "BYE")
            } finally {
                runCatching { socket.close() }
            }
            received to sent
        }
}
