package com.liuxue.assistant.data.transfer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Wi-Fi Direct 封装：完全离线，不经过路由器/互联网。
 *
 * 角色：
 *  - 创建方（Host）：createGroup 当 Group Owner，跑 TCP 服务；
 *  - 加入方（Join）：discoverPeers 找到对方 → connect；
 *  - 谁当 GO 由系统协商（创建方 groupOwnerIntent=15 尽量当 GO），两边都按 WifiP2pInfo.isGroupOwner
 *    决定"跑服务"还是"连过去"，所以即使角色反过来也能工作。
 */
class WifiDirectController(
    private val context: Context,
    private val onPeers: (List<WifiP2pDevice>) -> Unit,
    private val onConnectionInfo: (WifiP2pInfo, WifiP2pGroup?) -> Unit,
    private val onThisDevice: (WifiP2pDevice) -> Unit,
    private val onP2pEnabled: (Boolean) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    val supported: Boolean get() = manager != null

    fun register() {
        val m = manager
        if (m == null) {
            onError("这台设备不支持 Wi-Fi Direct")
            return
        }
        if (receiver != null) return
        channel = m.initialize(context, Looper.getMainLooper()) {
            onError("Wi-Fi Direct 初始化失败，请先打开 Wi-Fi")
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        onP2pEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val d = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as? WifiP2pDevice
                        if (d != null) onThisDevice(d)
                    }
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        requestThisDevice()
    }

    fun unregister() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        channel = null
    }

    fun discover() {
        val m = manager ?: return
        val ch = channel ?: return
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("正在搜索附近设备…（请确认对方已点「创建连接」）")
            override fun onFailure(reason: Int) = onError("搜索失败：" + reasonText(reason))
        })
    }

    fun createGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        // 先清掉可能残留的旧分组，否则 createGroup 会 BUSY
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = doCreateGroup(m, ch)
            override fun onFailure(reason: Int) = doCreateGroup(m, ch)
        })
    }

    private fun doCreateGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("连接已创建，等待对方加入…")
            override fun onFailure(reason: Int) {
                val r = reasonText(reason)
                if (reason == WifiP2pManager.BUSY || r.contains("BUSY")) doRemoveThenCreate(m, ch)
                else onError("创建连接失败：$r")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig().apply { groupOwnerIntent = 15 }
            m.createGroup(ch, config, listener)
        } else {
            m.createGroup(ch, listener)
        }
    }

    private fun doRemoveThenCreate(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = doCreateGroup(m, ch)
            override fun onFailure(reason: Int) = onError("创建连接失败：" + reasonText(reason))
        })
    }

    fun connect(device: WifiP2pDevice) {
        val m = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("已向「${device.deviceName}」发起连接…")
            override fun onFailure(reason: Int) = onError("连接失败：" + reasonText(reason))
        })
    }

    fun disconnect() {
        val m = manager ?: return
        val ch = channel ?: return
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("已断开连接")
            override fun onFailure(reason: Int) = onLog("断开连接：" + reasonText(reason))
        })
    }

    private fun requestPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        m.requestPeers(ch) { peers -> onPeers(peers.deviceList.toList().sortedBy { it.deviceName }) }
    }

    private fun requestConnectionInfo() {
        val m = manager ?: return
        val ch = channel ?: return
        m.requestConnectionInfo(ch) { info ->
            // 先给出基础连接信息；group 里带对方设备名，异步拿到后再回调一次补全
            onConnectionInfo(info, null)
            if (info.groupFormed) {
                runCatching { m.requestGroupInfo(ch) { g -> onConnectionInfo(info, g) } }
            }
        }
    }

    private fun requestThisDevice() {
        val m = manager ?: return
        val ch = channel ?: return
        // requestDeviceInfo 是 API 29+；低版本拿不到自己的设备名也不影响连接
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { m.requestDeviceInfo(ch) { d -> d?.let(onThisDevice) } }
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "本机不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "系统忙（可能已有连接），请重试"
        WifiP2pManager.ERROR -> "系统内部错误"
        else -> "错误码 $reason"
    }

    companion object {
        fun requiredPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, requiredPermission()) ==
                PackageManager.PERMISSION_GRANTED

        /** 权限属于哪个组（给 UI 选请求哪些） */
        fun permissionsToRequest(): Array<String> = arrayOf(requiredPermission())
    }
}
