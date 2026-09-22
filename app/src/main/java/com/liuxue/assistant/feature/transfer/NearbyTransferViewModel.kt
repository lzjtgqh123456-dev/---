package com.liuxue.assistant.feature.transfer

import android.app.Application
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.data.transfer.OutgoingFile
import com.liuxue.assistant.data.transfer.TransferClient
import com.liuxue.assistant.data.transfer.TransferFiles
import com.liuxue.assistant.data.transfer.TransferServer
import com.liuxue.assistant.data.transfer.WifiDirectController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

enum class NearbyRole { HOST, JOIN }

/** 本机准备发出去的文件 */
data class NearbyLocalFile(
    val id: Int,
    val name: String,
    val size: Long,
    val mime: String,
    val uri: Uri
)

/** 一条传输进度 */
data class NearbyProgress(
    val name: String,
    val direction: String, // 发送 / 接收
    val done: Long,
    val total: Long
)

data class NearbyUiState(
    val role: NearbyRole? = null,
    val supported: Boolean = true,
    val p2pEnabled: Boolean = false,
    val myDeviceName: String = "",
    val sessionCode: String = "",
    val peers: List<WifiP2pDevice> = emptyList(),
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val isGroupOwner: Boolean = false,
    val peerName: String = "",
    /** Group Owner 上当前连了几台接收端（一对多广播） */
    val peerCount: Int = 0,
    val status: String = "",
    val localFiles: List<NearbyLocalFile> = emptyList(),
    val received: List<String> = emptyList(),
    val transfers: List<NearbyProgress> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null
)

/**
 * 「面对面快传」：Wi-Fi Direct 完全离线，连接后一次完成双向互传
 * （加入方先把对方的文件拉下来，再把自己的文件推上去）。
 */
class NearbyTransferViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(NearbyUiState())
    val ui: StateFlow<NearbyUiState> = _ui.asStateFlow()

    private var hostAddress: String? = null

    private val server = TransferServer(
        context = app,
        scope = viewModelScope,
        onLog = { log(it) },
        onReceived = { name -> _ui.value = _ui.value.copy(received = _ui.value.received + name) }
    )

    private val controller = WifiDirectController(
        context = app,
        onPeers = { peers ->
            _ui.value = _ui.value.copy(peers = peers, scanning = false)
        },
        onConnectionInfo = { info, group -> onConnectionChanged(info, group) },
        onThisDevice = { d -> _ui.value = _ui.value.copy(myDeviceName = d.deviceName ?: "") },
        onP2pEnabled = { enabled ->
            _ui.value = _ui.value.copy(p2pEnabled = enabled)
            if (!enabled) log("Wi-Fi 已关闭，请打开 Wi-Fi 再试")
        },
        onLog = { log(it) },
        onError = { log(it) }
    )

    private fun log(text: String) {
        _ui.value = _ui.value.copy(status = text)
    }

    private fun message(text: String) {
        _ui.value = _ui.value.copy(message = text)
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    // ---------- 启动 ----------

    fun startHost() {
        if (!ensurePermission()) return
        val code = String.format("%04d", Random.nextInt(0, 10000))
        server.token = code
        controller.register()
        _ui.value = _ui.value.copy(
            role = NearbyRole.HOST,
            supported = controller.supported,
            sessionCode = code,
            connected = false,
            isGroupOwner = false,
            peerName = "",
            status = "正在创建连接…",
            localFiles = emptyList(),
            received = emptyList(),
            transfers = emptyList(),
            peers = emptyList(),
            scanning = false
        )
        controller.createGroup()
    }

    fun startJoin() {
        if (!ensurePermission()) return
        val code = _ui.value.sessionCode
        if (code.length != 4) {
            message("请先输入对方屏幕上显示的 4 位验证码")
            return
        }
        server.token = code
        controller.register()
        _ui.value = _ui.value.copy(
            role = NearbyRole.JOIN,
            supported = controller.supported,
            connected = false,
            isGroupOwner = false,
            peerName = "",
            status = "正在搜索附近设备…",
            localFiles = emptyList(),
            received = emptyList(),
            transfers = emptyList(),
            peers = emptyList(),
            scanning = true
        )
        controller.discover()
    }

    private fun ensurePermission(): Boolean {
        if (!WifiDirectController.hasPermission(getApplication())) {
            message("请先授予「附近的设备」权限")
            return false
        }
        return true
    }

    fun setSessionCode(code: String) {
        val digits = code.filter { it.isDigit() }.take(4)
        _ui.value = _ui.value.copy(sessionCode = digits)
    }

    fun refreshPeers() {
        if (_ui.value.role != NearbyRole.JOIN) return
        _ui.value = _ui.value.copy(scanning = true)
        controller.discover()
    }

    fun connect(device: WifiP2pDevice) {
        _ui.value = _ui.value.copy(scanning = false, status = "正在连接「${device.deviceName}」…")
        controller.connect(device)
    }

    fun stop() {
        runCatching { server.stop() }
        runCatching { controller.disconnect() }
        runCatching { controller.unregister() }
        hostAddress = null
        _ui.value = NearbyUiState(supported = controller.supported, myDeviceName = _ui.value.myDeviceName)
    }

    // ---------- 文件 ----------

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val name = TransferFiles.displayName(ctx, uri)
                        val size = TransferFiles.sizeOf(ctx, uri)
                        val mime = TransferFiles.guessMime(name)
                        NearbyLocalFile(
                            id = server.offer(name, size, mime) {
                                runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
                            },
                            name = name,
                            size = size,
                            mime = mime,
                            uri = uri
                        )
                    }.getOrNull()
                }
            }
            if (added.isEmpty()) message("没有读取到可发送的文件")
            _ui.value = _ui.value.copy(localFiles = _ui.value.localFiles + added)
        }
    }

    fun removeFile(id: Int) {
        server.remove(id)
        _ui.value = _ui.value.copy(localFiles = _ui.value.localFiles.filterNot { it.id == id })
    }

    // ---------- 连接状态 ----------

    private fun onConnectionChanged(info: WifiP2pInfo, group: WifiP2pGroup?) {
        if (!info.groupFormed) {
            _ui.value = _ui.value.copy(connected = false, isGroupOwner = false, status = "等待连接…")
            return
        }
        val owner = info.isGroupOwner
        hostAddress = info.groupOwnerAddress?.hostAddress
        val clients = group?.clientList?.mapNotNull { it.deviceName } ?: emptyList()
        val clientCount = clients.size
        val peer = if (owner) clients.firstOrNull() else group?.owner?.deviceName
        _ui.value = _ui.value.copy(
            connected = true,
            isGroupOwner = owner,
            peerName = peer ?: _ui.value.peerName,
            peerCount = if (owner) clientCount else _ui.value.peerCount,
            status = when {
                owner && clientCount > 0 ->
                    "已加入 $clientCount 台设备；每台在它自己那边点「开始互传」就会来拉文件"
                owner -> "连接已就绪，等待设备加入…（让对方用验证码 ${_ui.value.sessionCode} 连接）"
                else -> "已连接，点「开始互传」"
            }
        )
        if (owner) {
            // Group Owner 这一端跑服务；文件在加入本地列表时已经 offer 过了
            if (!server.start()) message("接收服务启动失败（端口可能被占用）")
        }
    }

    /** 「开始互传」：非 GO 端连到 GO，先收后发，一次完成双向 */
    fun startExchange() {
        val s = _ui.value
        val host = hostAddress
        if (s.busy) return
        if (!s.connected || host.isNullOrBlank()) {
            message("还没有连接好，请先连接对方")
            return
        }
        if (s.isGroupOwner) {
            message("这一端是接收方，等对方点「开始互传」即可")
            return
        }
        if (s.sessionCode.length != 4) {
            message("请输入对方的 4 位验证码")
            return
        }
        val ctx = getApplication<Application>()
        val outgoing = s.localFiles.map {
            OutgoingFile(it.name, it.size, it.mime) {
                runCatching { ctx.contentResolver.openInputStream(it.uri) }.getOrNull()
            }
        }
        _ui.value = s.copy(busy = true, transfers = emptyList(), status = "正在互传…")
        viewModelScope.launch {
            val result = runCatching {
                TransferClient(
                    context = ctx,
                    host = host,
                    token = s.sessionCode,
                    onLog = { log(it) },
                    onProgress = { name, dir, done, total ->
                        val list = _ui.value.transfers.filterNot { it.name == name && it.direction == dir }
                        _ui.value = _ui.value.copy(transfers = list + NearbyProgress(name, dir, done, total))
                    }
                ).exchange(outgoing)
            }
            result.fold(
                onSuccess = { (received, sent) ->
                    _ui.value = _ui.value.copy(
                        busy = false,
                        received = _ui.value.received + received,
                        status = "互传完成：接收 ${received.size} 个，发送 ${sent.size} 个"
                    )
                },
                onFailure = { e ->
                    val msg = e.message ?: e.javaClass.simpleName
                    _ui.value = _ui.value.copy(busy = false, status = "互传失败：$msg")
                    message("互传失败：$msg")
                }
            )
        }
    }

    /**
     * 本机自测（debug 用）：同一台手机上起服务端 + 客户端，走完整协议往返一个 8MB 文件，
     * 校验 SHA-256。用来在没有第二台手机时确认传输引擎是通的。
     */
    fun selfTest() {
        if (_ui.value.busy) return
        val ctx = getApplication<Application>()
        _ui.value = NearbyUiState(
            role = NearbyRole.HOST,
            sessionCode = "0000",
            status = "本机自测：正在准备测试文件…",
            busy = true
        )
        viewModelScope.launch {
            val result = runCatching {
                val dir = File(ctx.cacheDir, "nearby_selftest").apply {
                    deleteRecursively(); mkdirs()
                }
                val src = File(dir, "selftest_src.bin")
                val total = 8L * 1024 * 1024
                FileOutputStream(src).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var written = 0L
                    while (written < total) {
                        for (i in buf.indices) buf[i] = ((written + i) * 31 and 0xFF).toByte()
                        val n = minOf(buf.size.toLong(), total - written).toInt()
                        out.write(buf, 0, n)
                        written += n
                    }
                }
                val srcHash = sha256(src)
                val recvDir = File(dir, "recv").apply { mkdirs() }
                val testServer = TransferServer(
                    context = ctx,
                    token = "0000",
                    scope = viewModelScope,
                    onLog = { log(it) },
                    saveDir = recvDir
                )
                testServer.offer("selftest.bin", src.length(), "application/octet-stream") {
                    src.inputStream()
                }
                testServer.start()
                delay(800)
                _ui.value = _ui.value.copy(status = "本机自测：3 个接收端并发拉同一个 8 MB 文件…")
                val allOk = runCatching {
                    coroutineScope {
                        (1..3).map { i ->
                            async {
                                val clientDir = File(recvDir, "client$i").apply { mkdirs() }
                                TransferClient(
                                    context = ctx,
                                    host = "127.0.0.1",
                                    token = "0000",
                                    onLog = {},
                                    onProgress = { name, dirName, done, t ->
                                        val list = _ui.value.transfers
                                            .filterNot { it.name == "[$i] $name" && it.direction == dirName }
                                        _ui.value = _ui.value.copy(
                                            transfers = list + NearbyProgress("[$i] $name", dirName, done, t)
                                        )
                                    },
                                    saveDir = clientDir
                                ).exchange(emptyList())
                                val recv = File(clientDir, "selftest.bin")
                                if (!recv.exists() || recv.length() != src.length()) return@async false
                                sha256(recv) == srcHash
                            }
                        }.awaitAll().all { it }
                    }
                }.onFailure { testServer.stop(); throw it }.getOrThrow()
                testServer.stop()
                if (!allOk) error("并发自测有接收端校验失败")
                "本机自测通过：3 个接收端并发各拉 8 MB，SHA-256 全一致 ✅（一对多验证）"
            }
            _ui.value = _ui.value.copy(
                busy = false,
                status = result.getOrElse {
                    "本机自测失败：" + (it.message ?: it.javaClass.simpleName)
                },
                message = result.getOrNull()
            )
        }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    override fun onCleared() {
        runCatching { server.stop() }
        runCatching { controller.unregister() }
        super.onCleared()
    }
}
