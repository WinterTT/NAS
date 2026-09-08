package com.example.myupnp.ssdp

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * SSDP 发现引擎（纯手写，不用任何 UPnP 库）
 * ------------------------------------------------------------------
 * 原理：UPnP 设备发现依赖 UDP 组播。
 *  - 组播组地址：239.255.255.250，端口 1900（IANA 分配，见 RFC 2365）
 *
 * 本引擎做三件事：
 *  1. 周期性发送 M-SEARCH，主动问"局域网里有哪些 UPnP 设备"
 *  2. 监听设备的 HTTP 200 单播应答
 *  3. 顺带接收设备主动广播的 NOTIFY alive / byebye（不搜索也能感知设备上下线）
 *
 * 踩过的坑（重要！）：
 *  - M-SEARCH 不能从"绑定了 1900 端口的 socket"发出。设备的应答是单播
 *    回给"M-SEARCH 包的源 IP:源端口"的；如果源端口 = 1900，在 Windows 上
 *    应答会被防火墙/系统 SSDP 服务拦截，在部分 Android 环境也会异常收不到。
 *    正确做法（也是 UPnP 控制点惯例）：用一个**临时端口**的 socket 发搜索、
 *    收应答；另开一个尽量绑 1900 的组播 socket 专门收 NOTIFY。
 *  - Android 收到组播包前必须先拿 WifiManager.MulticastLock（在 Activity 里获取）。
 *  - 网络操作必须在后台线程，这里自己起了两个 worker 线程。
 */
class SsdpDiscovery(private val listener: Listener) {

    /** 引擎事件回调（都在 worker 线程调用，UI 层注意切主线程） */
    interface Listener {
        fun onSsdpMessage(message: SsdpMessage)
        fun onEngineError(error: Throwable)

        /** 引擎运行信息（端口/网卡等，排障用），默认空实现，UI 可覆写 */
        fun onSsdpInfo(info: String) {}
    }

    companion object {
        const val GROUP_ADDRESS = "239.255.255.250"
        const val PORT = 1900
        const val SEARCH_INTERVAL_MS = 15_000L // 周期性 M-SEARCH；上线/下线感知主要靠 NOTIFY，不必扫太勤
        const val SOCKET_TIMEOUT_MS = 1_000

        /** 标准的 M-SEARCH 请求体（注意行尾必须是 \r\n） */
        private fun buildMSearch(): ByteArray =
            buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $GROUP_ADDRESS:$PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: ssdp:all\r\n")
                append("USER-AGENT: MyUPNP/1.0 Android\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
    }

    @Volatile
    private var running = false

    /** 组播监听 socket：尽量绑 1900 收 NOTIFY（绑不上就退化为临时端口） */
    private var listenSocket: MulticastSocket? = null

    /** 搜索/应答 socket：绑临时端口发 M-SEARCH，收设备单播应答 */
    private var sendSocket: MulticastSocket? = null

    private var searchThread: Thread? = null
    private var listenThread: Thread? = null

    /** 启动引擎（幂等）。会立即发送第一批 M-SEARCH */
    fun start() {
        if (running) return
        running = true
        searchThread = Thread({ searchLoop() }, "ssdp-search").also { it.start() }
        listenThread = Thread({ listenLoop() }, "ssdp-notify").also { it.start() }
    }

    /** 停止引擎：关闭 socket 让 receive() 立即返回，线程自然退出 */
    fun stop() {
        running = false
        runCatching { listenSocket?.close() }
        runCatching { sendSocket?.close() }
        listenSocket = null
        sendSocket = null
    }

    fun isRunning(): Boolean = running

    /**
     * 立即主动搜索一次（不等周期定时）。
     * 用于用户手动"刷新设备"：刚开机/刚上线但还没到下一个周期的设备能立刻被问到。
     * 线程安全：仅读 sendSocket，并包 try（stop 时可能为 null/已关闭）。
     */
    fun forceSearch() {
        if (!running) return
        runCatching {
            sendSocket?.let { sock ->
                sendMSearch(sock)
                listener.onSsdpInfo("手动触发 M-SEARCH（刷新设备）")
            }
        }
    }

    // ------------------------------------------------------------------
    // 线程 A：周期发 M-SEARCH + 收单播应答
    // ------------------------------------------------------------------
    private fun searchLoop() {
        try {
            val sock = createSendSocket()
            sendSocket = sock
            if (sock.localPort > 0) {
                listener.onSsdpInfo("搜索 socket: 本地端口 ${sock.localPort}（临时端口，收单播应答）")
            }

            var lastSearchAt = 0L
            val buffer = ByteArray(65535)
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastSearchAt >= SEARCH_INTERVAL_MS) {
                    sendMSearch(sock)
                    lastSearchAt = now
                    listener.onSsdpInfo("已发送 M-SEARCH（每 $SEARCH_INTERVAL_MS ms 一次）")
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    dispatch(packet)
                } catch (_: SocketTimeoutException) {
                    // 超时正常，回到循环头检查是否该发下一次搜索
                } catch (e: SocketException) {
                    if (running) listener.onEngineError(e) // 被 stop() 关闭时不算错误
                }
            }
        } catch (e: Exception) {
            if (running) listener.onEngineError(e)
        } finally {
            runCatching { sendSocket?.close() }
            sendSocket = null
        }
    }

    // ------------------------------------------------------------------
    // 线程 B：一直蹲在组播组里收 NOTIFY alive/byebye
    // ------------------------------------------------------------------
    private fun listenLoop() {
        try {
            val sock = createListenSocket()
            listenSocket = sock
            joinMulticastGroup(sock)

            val buffer = ByteArray(65535)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    dispatch(packet)
                } catch (_: SocketTimeoutException) {
                    // 正常超时
                } catch (e: SocketException) {
                    if (running) listener.onEngineError(e)
                }
            }
        } catch (e: Exception) {
            if (running) listener.onEngineError(e)
        } finally {
            runCatching { listenSocket?.close() }
            listenSocket = null
        }
    }

    // ------------------------------------------------------------------
    // Socket 创建
    // ------------------------------------------------------------------

    /**
     * 发 M-SEARCH / 收单播应答的 socket：绑【临时端口】而不是 1900！
     * 设备应答是单播回给源端口的，绑 1900 会让应答在系统层面被拦（见类注释）。
     * 若本机只有一个合适的网卡，就直接绑到它的 IP 上，保证从正确的网卡出去。
     */
    private fun createSendSocket(): MulticastSocket {
        val sock = MulticastSocket(null).apply {
            reuseAddress = true
            try {
                bind(InetSocketAddress(0)) // 通配临时端口：由路由表决定出口网卡
            } catch (_: Exception) {
                bind(InetSocketAddress(0))
            }
            soTimeout = SOCKET_TIMEOUT_MS.toInt()
            timeToLive = 2
            loopbackMode = false // 不接收自己发出的组播包
        }
        val soloIp = singleIpv4Candidate()
        if (soloIp != null) {
            // 只有一个合适网卡（典型手机：wlan0），显式绑到它，避免走错出口
            try {
                sock.close()
                val rebind = MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(soloIp, 0))
                    soTimeout = SOCKET_TIMEOUT_MS.toInt()
                    timeToLive = 2
                    loopbackMode = false
                }
                listener.onSsdpInfo("搜索 socket 绑定网卡: $soloIp")
                return rebind
            } catch (_: Exception) {
                // 绑指定 IP 失败就用回通配临时端口
            }
        }
        return sock
    }

    /**
     * 组播监听 socket：优先绑 1900 收 NOTIFY（通常能成功）；被占用就退化为临时端口，
     * 此时仍能靠"线程 A"收到 M-SEARCH 应答，只是收不到主动广播的 NOTIFY。
     */
    private fun createListenSocket(): MulticastSocket {
        val sock = MulticastSocket(null).apply {
            reuseAddress = true
            try {
                bind(InetSocketAddress(PORT))
                listener.onSsdpInfo("组播监听 socket 已绑定 $GROUP_ADDRESS:$PORT（可收 NOTIFY）")
            } catch (e: Exception) {
                bind(InetSocketAddress(0))
                listener.onSsdpInfo("绑定 $PORT 失败(${e.message})，退化为临时端口：只能收应答，收不到 NOTIFY")
            }
            soTimeout = SOCKET_TIMEOUT_MS.toInt()
            timeToLive = 2
            loopbackMode = false
            runCatching { receiveBufferSize = 65535 }
        }
        return sock
    }

    /**
     * 加入组播组。Android 上要"按网卡"加入：
     * 遍历所有非回环、支持组播的网卡（通常是 wlan0），逐个 join。
     */
    private fun joinMulticastGroup(sock: MulticastSocket) {
        val group = InetAddress.getByName(GROUP_ADDRESS)
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
        var joined = 0
        for (nif in interfaces) {
            try {
                if (!nif.isUp || nif.isLoopback || !nif.supportsMulticast()) continue
                sock.joinGroup(InetSocketAddress(group, PORT), nif)
                joined++
            } catch (_: Exception) {
                // 单个网卡失败不影响整体
            }
        }
        if (joined > 0) listener.onSsdpInfo("已加入 $joined 个网卡的组播组")
    }

    /** 发送 M-SEARCH。UDP 不可靠，连发 2 次提高命中率 */
    private fun sendMSearch(sock: MulticastSocket) {
        val data = buildMSearch()
        val group = InetAddress.getByName(GROUP_ADDRESS)
        repeat(2) {
            val packet = DatagramPacket(data, data.size, group, PORT)
            sock.send(packet)
        }
    }

    // ------------------------------------------------------------------
    // 收包分发
    // ------------------------------------------------------------------
    private fun dispatch(packet: DatagramPacket) {
        val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val sourceHost = (packet.socketAddress as InetSocketAddress).address.hostAddress ?: "?"
        val message = SsdpMessage.parse(raw, sourceHost)
        if (message.type != SsdpMessageType.OTHER) {
            listener.onSsdpMessage(message)
        }
    }

    /**
     * 如果本机只有一个"可用"的 IPv4 网卡（非回环、非虚拟、非 link-local），返回它的地址；
     * 有多个（PC 常见：有线 + 虚拟机网卡）返回 null，交给路由表决定出口。
     */
    private fun singleIpv4Candidate(): InetAddress? {
        val candidates = ArrayList<InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            try {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    val raw = addr.address ?: continue
                    if (raw.size != 4) continue // 只要 IPv4
                    val a0 = raw[0].toInt() and 0xFF
                    val a1 = raw[1].toInt() and 0xFF
                    if (a0 == 169 && a1 == 254) continue // link-local
                    if (a0 == 127) continue
                    candidates.add(addr)
                }
            } catch (_: Exception) {
            }
        }
        return if (candidates.size == 1) candidates[0] else null
    }
}
