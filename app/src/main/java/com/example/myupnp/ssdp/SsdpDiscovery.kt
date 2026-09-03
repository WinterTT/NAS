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
 *  - 加入组播组的设备会收到发给该组的所有 UDP 包
 *
 * 本引擎做三件事：
 *  1. 周期性发送 M-SEARCH，主动问"局域网里有哪些 UPnP 设备"
 *  2. 一直监听组播组，接收设备的 HTTP 200 应答
 *  3. 顺带接收设备主动广播的 NOTIFY alive / byebye（不搜索也能感知设备上下线）
 *
 * 注意（Android 专坑）：
 *  - 收到组播包前必须先拿到 WifiManager.MulticastLock，
 *    否则 Wi-Fi 网卡默认丢弃组播帧（在 Activity 里获取）。
 *  - 网络操作必须在后台线程，这里自己起了一个 worker 线程。
 */
class SsdpDiscovery(private val listener: Listener) {

    /** 引擎事件回调（都在 worker 线程调用，UI 层注意切主线程） */
    interface Listener {
        fun onSsdpMessage(message: SsdpMessage)
        fun onEngineError(error: Throwable)
    }

    companion object {
        const val GROUP_ADDRESS = "239.255.255.250"
        const val PORT = 1900
        const val SEARCH_INTERVAL_MS = 5_000L // 每 5 秒广播一次 M-SEARCH，保持列表新鲜
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
    private var socket: MulticastSocket? = null
    private var worker: Thread? = null

    /** 启动引擎（幂等）。会立即发送第一批 M-SEARCH */
    fun start() {
        if (running) return
        running = true
        worker = Thread({ runLoop() }, "ssdp-discovery").also { it.start() }
    }

    /** 停止引擎：关闭 socket 让 receive() 立即返回，线程自然退出 */
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    fun isRunning(): Boolean = running

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------
    private fun runLoop() {
        try {
            val sock = createSocket()
            socket = sock
            joinMulticastGroup(sock)

            var lastSearchAt = 0L
            val buffer = ByteArray(65535)

            while (running) {
                // 周期搜索：刚启动立即搜一次，之后每隔 SEARCH_INTERVAL_MS 再搜
                val now = System.currentTimeMillis()
                if (now - lastSearchAt >= SEARCH_INTERVAL_MS) {
                    sendMSearch(sock)
                    lastSearchAt = now
                }

                // 阻塞等一个包；超时(SOCKET_TIMEOUT_MS)后回来检查 running / 再发搜索
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val sourceHost = (packet.socketAddress as InetSocketAddress).address.hostAddress ?: "?"
                    val message = SsdpMessage.parse(raw, sourceHost)
                    if (message.type != SsdpMessageType.OTHER) {
                        listener.onSsdpMessage(message)
                    }
                } catch (_: SocketTimeoutException) {
                    // 超时是正常现象，回到循环头检查是否该发下一次搜索
                } catch (e: SocketException) {
                    if (running) listener.onEngineError(e) // 被 stop() 关闭时不算错误
                }
            }
        } catch (e: Exception) {
            if (running) listener.onEngineError(e)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }

    private fun createSocket(): MulticastSocket {
        // SO_REUSEADDR：允许和其它 UPnP 程序共用 1900 端口（设备经常同时被多个控制点监听）
        return MulticastSocket(null).apply {
            reuseAddress = true
            try {
                bind(InetSocketAddress(PORT)) // 绑定 1900：能收到 NOTIFY 广播
            } catch (e: Exception) {
                // 1900 被其它程序独占时退化为临时端口：仍能收到 M-SEARCH 应答
                // （应答是单播给我们的），只是收不到组播 NOTIFY
                bind(InetSocketAddress(0))
            }
            soTimeout = SOCKET_TIMEOUT_MS.toInt()
            timeToLive = 2
            loopbackMode = false // 不接收自己发出的组播包
        }
    }

    /**
     * 加入组播组。Android 上要"按网卡"加入：
     * 遍历所有非回环、支持组播的网卡（通常是 wlan0），逐个 join。
     */
    private fun joinMulticastGroup(sock: MulticastSocket) {
        val group = InetAddress.getByName(GROUP_ADDRESS)
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
        for (nif in interfaces) {
            try {
                if (!nif.isUp || nif.isLoopback || !nif.supportsMulticast()) continue
                sock.joinGroup(InetSocketAddress(group, PORT), nif)
            } catch (_: Exception) {
                // 单个网卡失败不影响整体
            }
        }
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
}
