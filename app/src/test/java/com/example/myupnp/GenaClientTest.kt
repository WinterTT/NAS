package com.example.myupnp

import com.example.myupnp.gena.GenaClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 第 3 课单元测试：GENA 客户端。
 * 用一个"裸 socket 假设备"（ServerSocket）接收 SUBSCRIBE/UNSUBSCRIBE，
 * 完全可控地回 SID / 500，验证客户端请求与解析逻辑。
 * （不用 com.sun HttpServer：它对 SUBSCRIBE 这类非标准方法支持不可靠）
 */
class GenaClientTest {

    private lateinit var fakeDevice: FakeDevice

    @Before
    fun setUp() {
        fakeDevice = FakeDevice()
        fakeDevice.start()
    }

    @After
    fun tearDown() {
        fakeDevice.stop()
    }

    @Test
    fun subscribe_readsSidAndSendsRequiredHeaders() {
        val result = GenaClient.subscribe(
            eventSubUrl = "http://127.0.0.1:${fakeDevice.port}/upnp/event/AVTransport",
            callbackUrl = "http://192.168.1.50:39000/cb",
            timeoutSec = 1800
        )

        assertTrue("订阅应成功: ${result.error}", result.ok)
        assertEquals("uuid:fake-sid-123", result.sid)
        assertEquals(1800, result.timeoutSec)

        val req = fakeDevice.lastRequest
        assertEquals("SUBSCRIBE", req.method)
        assertTrue(req.requestLine.contains("/upnp/event/AVTransport"))
        // CALLBACK 必须用尖括号包住（规范要求）
        assertEquals("<http://192.168.1.50:39000/cb>", req.header("callback"))
        assertEquals("upnp:event", req.header("nt"))
        assertEquals("Second-1800", req.header("timeout"))
    }

    @Test
    fun renew_sendsSid() {
        val result = GenaClient.renew(
            eventSubUrl = "http://127.0.0.1:${fakeDevice.port}/upnp/event/AVTransport",
            sid = "uuid:abc",
            timeoutSec = 900
        )

        assertTrue("续订应成功: ${result.error}", result.ok)
        assertEquals("SUBSCRIBE", fakeDevice.lastRequest.method)
        assertEquals("uuid:abc", fakeDevice.lastRequest.header("sid"))
        assertEquals("Second-900", fakeDevice.lastRequest.header("timeout"))
    }

    @Test
    fun unsubscribe_sendsSid() {
        val result = GenaClient.unsubscribe(
            eventSubUrl = "http://127.0.0.1:${fakeDevice.port}/upnp/event/AVTransport",
            sid = "uuid:abc"
        )

        assertTrue("退订应成功: ${result.error}", result.ok)
        assertEquals("UNSUBSCRIBE", fakeDevice.lastRequest.method)
        assertEquals("uuid:abc", fakeDevice.lastRequest.header("sid"))
    }

    @Test
    fun subscribe_serverError_reportsFailure() {
        fakeDevice.faultMode = true
        val result = GenaClient.subscribe(
            eventSubUrl = "http://127.0.0.1:${fakeDevice.port}/x",
            callbackUrl = "http://192.168.1.50:39000/cb"
        )
        assertEquals(false, result.ok)
        assertNotNull(result.error)
    }

    // ------------------------------------------------------------------
    // 裸 socket 假设备
    // ------------------------------------------------------------------
    private class FakeDevice {
        data class Behavior(val code: Int, val headers: Map<String, String>)
        data class Request(val requestLine: String, val headers: Map<String, String>) {
            val method: String get() = requestLine.substringBefore(' ')
            fun header(name: String): String? = headers[name.lowercase()]
        }

        private val serverSocket = ServerSocket(0)
        private val executor = Executors.newCachedThreadPool()
        val port: Int get() = serverSocket.localPort
        var faultMode = false
        var lastRequest: Request = Request("", emptyMap())

        /** 假设备对每种方法的响应方式 */
        private fun behaviorFor(method: String): Behavior = when (method) {
            "SUBSCRIBE" -> Behavior(
                200,
                mapOf("SID" to "uuid:fake-sid-123", "TIMEOUT" to "Second-1800")
            )
            else -> Behavior(200, emptyMap())
        }

        fun start() {
            Thread {
                while (!serverSocket.isClosed) {
                    val client = try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    executor.execute { handle(client) }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun handle(socket: Socket) {
            try {
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                )
                val requestLine = reader.readLine() ?: return
                val headers = HashMap<String, String>()
                var line: String?
                while (reader.readLine().also { line = it }?.isNotEmpty() == true) {
                    val idx = line!!.indexOf(':')
                    if (idx > 0) {
                        headers[line!!.substring(0, idx).trim().lowercase()] =
                            line!!.substring(idx + 1).trim()
                    }
                }
                lastRequest = Request(requestLine, headers)

                val behavior = if (faultMode) Behavior(500, emptyMap())
                else behaviorFor(requestLine.substringBefore(' '))

                val resp = buildString {
                    append("HTTP/1.1 ${behavior.code} " +
                        "${if (behavior.code == 200) "OK" else "Internal Server Error"}\r\n")
                    behavior.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("\r\n")
                }
                val out = socket.getOutputStream()
                out.write(resp.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
            }
        }

        fun stop() {
            runCatching { serverSocket.close() }
            executor.shutdownNow()
        }
    }
}
