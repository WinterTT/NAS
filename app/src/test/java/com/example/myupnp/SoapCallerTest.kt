package com.example.myupnp

import com.example.myupnp.soap.SoapCaller
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 第 2 课单元测试：SOAP 请求的组装与错误解析。
 * 在本机起一个假的 HTTP 设备端点，验证：
 *  1) 请求头带 SOAPACTION
 *  2) 请求体是合法 SOAP 信封且参数做了 XML 转义
 *  3) 200 -> success
 *  4) 500 + <s:Fault> -> 解析出 UPnP 错误码和描述
 */
class SoapCallerTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** 捕获最后一次收到的请求（供断言用） */
    private var lastSoapAction: String? = null
    private var lastBody: String? = null
    private var mode = "success"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        lastSoapAction = exchange.requestHeaders.getFirst("SOAPACTION")
        lastBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val (code, body) = when (mode) {
            "fault" -> 500 to FAULT_BODY
            else -> 200 to SUCCESS_BODY
        }
        exchange.responseHeaders.add("Content-Type", "text/xml; charset=\"utf-8\"")
        exchange.sendResponseHeaders(code, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun call_play_sendsCorrectHeadersAndBody() {
        val result = SoapCaller.call(
            controlUrl = "$baseUrl/upnp/control/AVTransport",
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            actionName = "Play",
            args = mapOf("InstanceID" to "0", "Speed" to "1")
        )

        assertTrue(result.success)
        assertEquals(200, result.httpCode)
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            lastSoapAction
        )
        val body = lastBody.orEmpty()
        assertTrue(body.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
        assertTrue(body.contains("<Speed>1</Speed>"))
        // 信封命名空间
        assertTrue(body.contains("http://schemas.xmlsoap.org/soap/envelope/"))
    }

    @Test
    fun call_xmlEscapesSpecialCharactersInArgs() {
        SoapCaller.call(
            controlUrl = "$baseUrl/x",
            serviceType = "urn:test:service:1",
            actionName = "Foo",
            args = mapOf("Value" to "a<b&c\"d")
        )
        val body = lastBody.orEmpty()
        assertTrue(body.contains("<Value>a&lt;b&amp;c&quot;d</Value>"))
        assertTrue(!body.contains("<Value>a<b&c\"d</Value>"))
    }

    @Test
    fun call_fault500_parsesUpnpError() {
        mode = "fault"
        val result = SoapCaller.call(
            controlUrl = "$baseUrl/x",
            serviceType = "urn:test:service:1",
            actionName = "NoSuchAction",
            args = emptyMap()
        )

        assertEquals(false, result.success)
        assertEquals(500, result.httpCode)
        assertEquals(701, result.upnpErrorCode) // 701 = "没有该动作"
        assertEquals("No such action", result.upnpErrorDesc)
        assertTrue(result.faultString.isNotEmpty())
        assertTrue(result.isFault)
    }

    companion object {
        private val SUCCESS_BODY = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        private val FAULT_BODY = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <s:Fault>
                  <faultcode>s:Client</faultcode>
                  <faultstring>UPnPError</faultstring>
                  <detail>
                    <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                      <errorCode>701</errorCode>
                      <errorDescription>No such action</errorDescription>
                    </UPnPError>
                  </detail>
                </s:Fault>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }
}
