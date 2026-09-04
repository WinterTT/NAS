package com.example.myupnp.gena

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * GENA 客户端：订阅 / 续订 / 退订 服务事件
 * ------------------------------------------------------------------
 * GENA(General Event Notification Architecture) 是 UPnP 的"事件推送"协议，
 * 和 SSDP/SOAP 最大的不同：**数据流方向反过来**——不是我们去设备取，
 * 而是设备状态一变，主动把新值推到我们注册的回调地址。
 *
 * 订阅三步走（都是裸 HTTP，因为 HttpURLConnection 不支持 SUBSCRIBE 方法）：
 *
 *  1) SUBSCRIBE（注册，带回调地址）：
 *     SUBSCRIBE /upnp/event/AVTransport HTTP/1.1
 *     HOST: 192.168.1.10:8200
 *     CALLBACK: <http://192.168.1.50:39000/cb>   <- 我们手机上的 HTTP 服务
 *     NT: upnp:event                             <- 通知类型，写死
 *     TIMEOUT: Second-1800                       <- 订阅有效时长（设备给上限）
 *     设备回：HTTP/1.1 200 OK + SID: uuid:xxxx（订阅凭证）+ TIMEOUT
 *
 *  2) 续订 RENEW（到期前延长，只需带 SID）：
 *     SUBSCRIBE /upnp/event/AVTransport HTTP/1.1
 *     SID: uuid:xxxx
 *     TIMEOUT: Second-1800
 *
 *  3) 退订 UNSUBSCRIBE：
 *     UNSUBSCRIBE /upnp/event/AVTransport HTTP/1.1
 *     SID: uuid:xxxx
 */
object GenaClient {

    /** 订阅结果 */
    data class GenResult(
        val ok: Boolean,
        val sid: String = "",
        val timeoutSec: Int = 0,
        val message: String = ""
    ) {
        val error: String? get() = if (ok) null else message
    }

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    /** 订阅：让设备开始把事件推到 callbackUrl */
    fun subscribe(
        eventSubUrl: String,
        callbackUrl: String,
        timeoutSec: Int = 1800
    ): GenResult {
        val headers = linkedMapOf(
            "CALLBACK" to "<$callbackUrl>",      // 必须用尖括号包住，规范要求
            "NT" to "upnp:event",
            "TIMEOUT" to "Second-$timeoutSec"
        )
        return rawRequest("SUBSCRIBE", eventSubUrl, headers)
    }

    /** 续订：订阅快到期时，带 SID 再 SUBSCRIBE 一次即可延长 */
    fun renew(eventSubUrl: String, sid: String, timeoutSec: Int = 1800): GenResult {
        val headers = linkedMapOf(
            "SID" to sid,
            "TIMEOUT" to "Second-$timeoutSec"
        )
        return rawRequest("SUBSCRIBE", eventSubUrl, headers)
    }

    /** 退订：告诉设备我不再收事件了（设备端会清理订阅） */
    fun unsubscribe(eventSubUrl: String, sid: String): GenResult {
        val headers = linkedMapOf("SID" to sid)
        return rawRequest("UNSUBSCRIBE", eventSubUrl, headers)
    }

    /**
     * 裸 HTTP 请求：手写请求行 + 首部，逐行读响应。
     * HttpURLConnection 只允许标准方法，SUBSCRIBE/UNSUBSCRIBE 必须这么干。
     */
    private fun rawRequest(
        method: String,
        urlString: String,
        headers: Map<String, String>
    ): GenResult {
        val socket = Socket()
        return try {
            val url = URL(urlString)
            val port = if (url.port > 0) url.port else 80
            socket.connect(InetSocketAddress(url.host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            // ---- 写请求 ----
            // 注意：只能用 flush()，绝不能 close() 输出流 ——
            // Socket 的 OutputStream.close() 会连带关闭整个 socket，
            // 导致下面的响应还没读完连接就断了。
            val path = url.path.ifEmpty { "/" } +
                (if (url.query != null) "?${url.query}" else "")
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("HOST: ${url.host}:$port\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("USER-AGENT: MyUPNP/1.0 (Android)\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            // ---- 读响应：状态行 + 首部 ----
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: return GenResult(false, message = "无响应")
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

            val respHeaders = HashMap<String, String>()
            var line: String?
            while (reader.readLine().also { line = it }?.isNotEmpty() == true) {
                val idx = line!!.indexOf(':')
                if (idx > 0) {
                    respHeaders[line!!.substring(0, idx).trim().lowercase()] =
                        line!!.substring(idx + 1).trim()
                }
            }

            if (code != 200) {
                return GenResult(false, message = "HTTP $code")
            }
            val sid = respHeaders["sid"] ?: ""
            val timeout = parseTimeout(respHeaders["timeout"])
            if (method == "SUBSCRIBE" && sid.isEmpty()) {
                return GenResult(false, message = "响应缺 SID")
            }
            GenResult(ok = true, sid = sid, timeoutSec = timeout)
        } catch (e: Exception) {
            GenResult(false, message = e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** "Second-1800" / "infinite" -> 秒数 */
    private fun parseTimeout(v: String?): Int {
        if (v == null) return 0
        return when {
            v.equals("infinite", ignoreCase = true) -> 0
            v.startsWith("Second-", ignoreCase = true) ->
                v.substringAfter('-').toIntOrNull() ?: 0
            else -> 0
        }
    }
}
