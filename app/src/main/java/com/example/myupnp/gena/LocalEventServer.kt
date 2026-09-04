package com.example.myupnp.gena

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * App 内嵌的轻量 HTTP 回调服务器
 * ------------------------------------------------------------------
 * GENA 要求控制点提供一个"能被设备访问到的 HTTP 地址"（CALLBACK），
 * 设备状态变化时往这个地址发 NOTIFY 推送。所以在 Android 上，
 * **控制点自己也要当一次 HTTP 服务器**——这是理解 UPnP 最反直觉的一点。
 *
 * 本类手写了一个最小 HTTP/1.1 服务器（只服务 GENA 场景，够用就行）：
 *  - 监听 0.0.0.0 的随机端口（>1024，免 root）
 *  - 收到请求：读请求行 + 首部 + Content-Length 正文
 *  - 识别 NOTIFY（设备推送），把正文交给 listener
 *  - 一律快速回 200，不让设备傻等
 *
 * 注意：本类刻意不使用任何 android.* 类，因此可以在 JVM 单测里完整验证
 * （起一个真 socket 发 NOTIFY，检查能不能收、解析、回 200）。
 */
class LocalEventServer(private val listener: Listener) {

    interface Listener {
        /**
         * 收到一条设备推送。
         * @param remote  设备地址
         * @param sid     订阅号（判断是哪次订阅推来的）
         * @param nts     通知子类型，正常是 upnp:propchange
         * @param body    XML 正文（<e:propertyset>...）
         */
        fun onEvent(remote: String, sid: String?, nts: String?, body: String)

        /** 服务器运行信息（端口等），排障用 */
        fun onInfo(info: String) {}
    }

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    @Volatile
    private var running = false

    /** 服务器实际监听的端口（start 之后才有效） */
    val port: Int get() = serverSocket?.localPort ?: 0

    fun start() {
        if (running) return
        running = true
        val sock = ServerSocket(0) // 0 = 让系统分配一个空闲端口
        serverSocket = sock
        listener.onInfo("事件回调服务器已监听端口 ${sock.localPort}")

        executor = Executors.newCachedThreadPool { r -> Thread(r, "event-callback").apply { isDaemon = true } }
        Thread({
            while (running) {
                try {
                    val client = sock.accept()
                    executor?.execute { handle(client) }
                } catch (e: Exception) {
                    if (running) listener.onInfo("accept 异常: ${e.message}")
                }
            }
        }, "event-server-accept").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        executor?.shutdownNow()
        executor = null
        serverSocket = null
    }

    // ------------------------------------------------------------------
    // 单连接处理：读请求 -> 解析 -> 回 200
    // ------------------------------------------------------------------
    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1) 读首部（到空行为止）。HEADER_MAX 防止恶意/坏设备无限刷
            val headerBytes = ArrayList<Byte>()
            var sawCrlfCrlf = false
            while (headerBytes.size < HEADER_MAX_BYTES) {
                val b = input.read()
                if (b < 0) return
                headerBytes.add(b.toByte())
                if (headerBytes.size >= 4 &&
                    headerBytes[headerBytes.size - 4] == '\r'.code.toByte() &&
                    headerBytes[headerBytes.size - 3] == '\n'.code.toByte() &&
                    headerBytes[headerBytes.size - 2] == '\r'.code.toByte() &&
                    headerBytes[headerBytes.size - 1] == '\n'.code.toByte()
                ) {
                    sawCrlfCrlf = true
                    break
                }
            }
            if (!sawCrlfCrlf) return

            val headerText = String(headerBytes.toByteArray(), Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""

            // 2) 首部 -> map（小写 key）
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            // 3) 按 Content-Length 读正文（有则读，没有跳过）
            var body = ""
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength in 1..BODY_MAX_BYTES) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read, Charsets.UTF_8)
            }

            // 4) 快速回 200（GENA 规范：收到事件回 200 即可，不需要响应体）
            val resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(resp.toByteArray(Charsets.UTF_8))
            output.flush()

            // 5) 只关心 NOTIFY：设备的事件推送
            if (method.equals("NOTIFY", ignoreCase = true)) {
                val remote = socket.inetAddress?.hostAddress ?: "?"
                listener.onEvent(
                    remote = remote,
                    sid = headers["sid"],
                    nts = headers["nts"],
                    body = body
                )
            }
        } catch (e: SocketTimeoutException) {
            // 读超时：丢弃这个连接即可
        } catch (_: Exception) {
            // 任何单连接异常都不影响服务器继续跑
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val HEADER_MAX_BYTES = 16 * 1024
        private const val BODY_MAX_BYTES = 512 * 1024
    }
}

/** 找本机对外可达的 IPv4 地址（组播/Wi-Fi 场景，给回调 URL 用） */
object LocalIp {
    /** 返回第一个非回环、非 link-local 的 IPv4 地址；找不到返回 null */
    fun ipv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is InetAddress && addr.address.size == 4) {
                    val raw = addr.address
                    val a0 = raw[0].toInt() and 0xFF
                    val a1 = raw[1].toInt() and 0xFF
                    if (a0 == 169 && a1 == 254) continue // link-local 不可路由
                    if (a0 == 127) continue
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}
