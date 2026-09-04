package com.example.myupnp

import com.example.myupnp.gena.EventProperties
import com.example.myupnp.gena.LocalEventServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 第 3 课单元测试：App 内嵌回调服务器（LocalEventServer）。
 * 直接起服务器，用真 socket 扮演"设备"发一帧 NOTIFY 推送，
 * 验证：收到正文、能解析 SID/NTS、EventProperties 能抠出 (变量名,新值)。
 */
class LocalEventServerTest {

    private lateinit var server: LocalEventServer
    private var port: Int = 0

    @Before
    fun setUp() {
        server = LocalEventServer(object : LocalEventServer.Listener {
            override fun onEvent(remote: String, sid: String?, nts: String?, body: String) {
                receivedSid = sid
                receivedNts = nts
                receivedBody = body
                latch.countDown()
            }
        })
        server.start()
        // 轮询等端口就绪
        var attempts = 0
        while (server.port == 0 && attempts < 100) {
            Thread.sleep(10)
            attempts++
        }
        port = server.port
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private var receivedSid: String? = null
    private var receivedNts: String? = null
    private var receivedBody: String? = null
    private val latch = CountDownLatch(1)

    /** 发一帧典型的 GENA NOTIFY（设备推送音量变化） */
    @Test
    fun receivesNotifyAndParsesProperties() {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
              <e:property>
                <Volume>45</Volume>
                <Mute>0</Mute>
              </e:property>
            </e:propertyset>
        """.trimIndent()

        val notify = buildString {
            append("NOTIFY /upnp/event/cb HTTP/1.1\r\n")
            append("HOST: 127.0.0.1:$port\r\n")
            append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
            append("NT: upnp:event\r\n")
            append("NTS: upnp:propchange\r\n")
            append("SID: uuid:sub-001\r\n")
            append("SEQ: 1\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("\r\n")
            append(body)
        }

        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(notify.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            // 读响应（服务器会回 200）
            val resp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val status = resp.readLine()
            assertTrue("应回 HTTP 200，实际: $status", status.startsWith("HTTP/1.1 200"))
        }

        assertTrue("应收到事件", latch.await(5, TimeUnit.SECONDS))
        assertEquals("uuid:sub-001", receivedSid)
        assertEquals("upnp:propchange", receivedNts)

        val props = EventProperties.parse(receivedBody.orEmpty())
        assertEquals(listOf("Volume" to "45", "Mute" to "0"), props)
    }

    /** 空正文（设备异常）不应让服务器崩溃 */
    @Test
    fun emptyBody_isTolerated() {
        val notify = "NOTIFY /cb HTTP/1.1\r\nHOST: 127.0.0.1:$port\r\n\r\n"
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(notify.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val resp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val status = resp.readLine()
            assertTrue(status.startsWith("HTTP/1.1 200"))
        }
        // 服务器没崩、还能再收下一个请求即可（通过再次连接验证）
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(notify.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val resp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            assertTrue(resp.readLine().startsWith("HTTP/1.1 200"))
        }
    }
}
