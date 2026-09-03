package com.example.myupnp

import com.example.myupnp.ssdp.SsdpMessage
import com.example.myupnp.ssdp.SsdpMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第 1 课单元测试：验证 SSDP 报文解析。
 * 用真实报文样例（路由器/媒体设备常见的格式）验证字段提取。
 */
class SsdpMessageTest {

    /** M-SEARCH 的应答（设备单播回来的 200 OK） */
    @Test
    fun parse_searchResponse() {
        val raw = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            DATE: Thu, 01 Jan 2026 00:00:00 GMT
            EXT:
            LOCATION: http://192.168.1.50:49152/rootDesc.xml
            SERVER: Linux/3.4 UPnP/1.0
            ST: upnp:rootdevice
            USN: uuid:3f2a1b4c-0000-1000-8000-010203040506::upnp:rootdevice
        """.trimIndent().replace("\n", "\r\n")

        val msg = SsdpMessage.parse(raw, "192.168.1.50")

        assertEquals(SsdpMessageType.SEARCH_RESPONSE, msg.type)
        assertEquals("http://192.168.1.50:49152/rootDesc.xml", msg.location)
        assertEquals(
            "uuid:3f2a1b4c-0000-1000-8000-010203040506::upnp:rootdevice",
            msg.usn
        )
        // 首部 key 转小写
        assertEquals("max-age=1800", msg.headers["cache-control"])
    }

    /** 设备上线主动广播 NOTIFY alive */
    @Test
    fun parse_notifyAlive() {
        val raw = """
            NOTIFY * HTTP/1.1
            HOST: 239.255.255.250:1900
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.1.50:49152/rootDesc.xml
            NT: upnp:rootdevice
            NTS: ssdp:alive
            SERVER: Linux/3.4 UPnP/1.0
            USN: uuid:3f2a1b4c-0000-1000-8000-010203040506::upnp:rootdevice
        """.trimIndent().replace("\n", "\r\n")

        val msg = SsdpMessage.parse(raw, "192.168.1.50")
        assertEquals(SsdpMessageType.NOTIFY_ALIVE, msg.type)
        assertEquals("upnp:rootdevice", msg.nt)
    }

    /** 设备下线广播 NOTIFY byebye */
    @Test
    fun parse_notifyByeBye() {
        val raw = """
            NOTIFY * HTTP/1.1
            HOST: 239.255.255.250:1900
            NT: upnp:rootdevice
            NTS: ssdp:byebye
            USN: uuid:3f2a1b4c-0000-1000-8000-010203040506::upnp:rootdevice
        """.trimIndent().replace("\n", "\r\n")

        val msg = SsdpMessage.parse(raw, "192.168.1.50")
        assertEquals(SsdpMessageType.NOTIFY_BYEBYE, msg.type)
    }

    /** 别人发的 M-SEARCH 对控制点无意义，应归类为 OTHER */
    @Test
    fun parse_foreignMSearch_isOther() {
        val raw = """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 1
            ST: ssdp:all
        """.trimIndent().replace("\n", "\r\n")

        val msg = SsdpMessage.parse(raw, "192.168.1.10")
        assertEquals(SsdpMessageType.OTHER, msg.type)
        assertNull(msg.location)
    }
}
