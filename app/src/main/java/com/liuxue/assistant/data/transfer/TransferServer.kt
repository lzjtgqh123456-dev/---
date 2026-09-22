package com.liuxue.assistant.data.transfer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** 本机要发给对方的文件（持有 SAF uri，需要时才打开流） */
data class SharedFile(
    val id: Int,
    val name: String,
    val size: Long,
    val mime: String,
    val open: () -> InputStream?
)

/**
 * 跑在 Wi-Fi Direct Group Owner 上的接收/发送服务。
 *
 * - 对方可以 LIST + GET 下载本机文件；
 * - 对方可以 PUT 把文件传过来，直接落到系统「下载/留学助手快传」。
 *
 * 验证码不对直接断开，避免同一 P2P 网络里的其它设备乱拿文件。
 */
class TransferServer(
    private val context: Context,
    /** 4 位验证码；创建连接时才知道，所以是可变的 */
    @Volatile var token: String = "",
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
    private val onReceived: (String) -> Unit = {},
    /** 自测用：指定接收目录；null = 系统「下载/留学助手快传」 */
    private val saveDir: java.io.File? = null
) {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val nextId = AtomicInteger(1)
    private val files = LinkedHashMap<Int, SharedFile>()
    /** 多台设备同时上传时串行落盘，避免同名文件互相覆盖 */
    private val saveLock = Any()

    fun list(): List<SharedFile> = synchronized(files) { files.values.toList() }

    fun offer(name: String, size: Long, mime: String, open: () -> InputStream?): Int {
        val id = nextId.getAndIncrement()
        synchronized(files) { files[id] = SharedFile(id, name, size, mime, open) }
        return id
    }

    fun remove(id: Int) = synchronized(files) { files.remove(id) }

    fun clear() = synchronized(files) { files.clear() }

    fun start(): Boolean {
        if (serverSocket != null) return true
        // ServerSocket 的创建/accept 必须离开主线程，否则 NetworkOnMainThreadException
        acceptJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val ss = ServerSocket(TransferProtocol.PORT)
                serverSocket = ss
                onLog("已开启接收，等待对方连接…")
                while (isActive && !ss.isClosed) {
                    val socket = runCatching { ss.accept() }.getOrNull() ?: continue
                    launch(Dispatchers.IO) { handle(socket) }
                }
            }.onFailure {
                onLog("服务启动失败：" + (it.message ?: it.javaClass.simpleName))
            }
        }
        return true
    }

    fun stop() {
        runCatching { acceptJob?.cancel() }
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching { s.soTimeout = 20_000 }
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            try {
                val hello = TransferProtocol.readLine(input) ?: return
                val parts = hello.split(" ")
                if (parts.getOrNull(0) != "HELLO" || parts.getOrNull(1) != token) {
                    TransferProtocol.writeLine(output, "ERR 验证码不正确")
                    onLog("有设备验证码不正确，已拒绝")
                    return
                }
                TransferProtocol.writeLine(output, "OK")
                onLog("对方已连接")
                while (true) {
                    val line = TransferProtocol.readLine(input) ?: break
                    when {
                        line == "LIST" -> {
                            val list = list()
                            TransferProtocol.writeLine(output, "FILES ${list.size}")
                            list.forEach { f ->
                                TransferProtocol.writeLine(
                                    output,
                                    "${f.id}\t${f.size}\t${f.mime}\t${TransferProtocol.enc(f.name)}"
                                )
                            }
                        }

                        line.startsWith("GET ") -> {
                            val id = line.removePrefix("GET ").trim().toIntOrNull()
                            val f = id?.let { synchronized(files) { files[it] } }
                            if (f == null) {
                                TransferProtocol.writeLine(output, "ERR 文件不存在")
                                continue
                            }
                            TransferProtocol.writeLine(output, "DATA ${f.size}")
                            onLog("对方正在下载：" + f.name)
                            val stream = f.open()
                            if (stream == null) {
                                // 声明了大小就必须发够，发不出去只能断开
                                onLog("读取失败：" + f.name)
                                return
                            }
                            stream.use {
                                TransferProtocol.copyExactly(it, output, f.size) { done ->
                                    if (done == f.size) onLog("已发送：" + f.name)
                                }
                            }
                        }

                        line.startsWith("PUT ") -> {
                            val p = line.split(" ")
                            val size = p.getOrNull(1)?.toLongOrNull()
                            val name = p.getOrNull(2)?.let {
                                runCatching { TransferProtocol.dec(it) }.getOrNull()
                            } ?: "received.bin"
                            if (size == null || size < 0) {
                                TransferProtocol.writeLine(output, "ERR 参数错误")
                                continue
                            }
                            TransferProtocol.writeLine(output, "READY")
                            onLog("正在接收：" + name)
                            val saved = synchronized(saveLock) {
                                if (saveDir != null) {
                                    val f = TransferFiles.uniqueFile(saveDir, TransferFiles.sanitize(name))
                                    f.outputStream().use { out ->
                                        TransferProtocol.copyExactly(input, out, size)
                                    }
                                    f.name
                                } else {
                                    TransferFiles.saveToDownloads(context, name, size, input) { }
                                }
                            }
                            TransferProtocol.writeLine(output, "DONE ${TransferProtocol.enc(saved)}")
                            onLog("已接收：" + saved)
                            onReceived(saved)
                        }

                        line == "BYE" -> break
                        else -> TransferProtocol.writeLine(output, "ERR 未知命令")
                    }
                }
            } catch (e: Exception) {
                onLog("连接结束：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
