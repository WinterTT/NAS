package com.example.myupnp

import com.example.myupnp.dlna.ContentDirectoryClient
import com.example.myupnp.model.MediaContainer
import com.example.myupnp.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 5 课单元测试：ContentDirectory Browse 响应解析。
 * 验证能从 SOAP 响应里抽出 Result（转义 DIDL），并解析出容器/条目。
 */
class ContentDirectoryClientTest {

    /** 模拟一台 MediaServer 返回的 Browse 响应（Result 是实体转义过的 DIDL） */
    private val soapBody = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
              <Result>
                &lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                  xmlns:dc="http://purl.org/dc/elements/1.1/"
                  xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;
                  &lt;container id="2" parentID="0" restricted="1"&gt;
                    &lt;dc:title&gt;流行&lt;/dc:title&gt;
                    &lt;upnp:class&gt;object.container.storageFolder&lt;/upnp:class&gt;
                    &lt;container:childCount&gt;8&lt;/container:childCount&gt;
                  &lt;/container&gt;
                  &lt;item id="12" parentID="0" restricted="1"&gt;
                    &lt;dc:title&gt;晴天.mp3&lt;/dc:title&gt;
                    &lt;upnp:class&gt;object.item.audioItem.musicTrack&lt;/upnp:class&gt;
                    &lt;res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_PN=MP3"&gt;
                      http://192.168.1.10:8200/music/qingtian.mp3
                    &lt;/res&gt;
                  &lt;/item&gt;
                &lt;/DIDL-Lite&gt;
              </Result>
              <NumberReturned>2</NumberReturned>
              <TotalMatches>2</TotalMatches>
            </u:BrowseResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    @Test
    fun extractResult_unescapesDidl() {
        val didl = ContentDirectoryClient.extractResult(soapBody)
        assertTrue(didl != null)
        assertTrue(didl!!.contains("<container id=\"2\""))      // 转义已被反转
        assertTrue(didl.contains("<item id=\"12\""))
        assertTrue(!didl.contains("&lt;container"))
    }

    @Test
    fun parseResult_containersAndItems() {
        val didl = ContentDirectoryClient.extractResult(soapBody)!!
        val objects = ContentDirectoryClient.parseResult(didl)

        assertEquals(2, objects.size)

        // 第一个：容器
        val container = objects[0] as MediaContainer
        assertEquals("2", container.id)
        assertEquals("流行", container.title)
        assertTrue(container.isContainer)
        assertEquals(8, container.childCount)

        // 第二个：音频条目
        val item = objects[1] as MediaItem
        assertEquals("12", item.id)
        assertEquals("晴天.mp3", item.title)
        assertEquals("object.item.audioItem.musicTrack", item.upnpClass)
        assertEquals("http://192.168.1.10:8200/music/qingtian.mp3", item.resUrl.trim())
        assertEquals("audio/mpeg", item.mime)
        assertTrue(!item.isContainer)
    }

    /** 空目录：只有 DIDL-Lite 壳，没有 container/item */
    @Test
    fun parseResult_emptyLibrary() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"/>"""
        assertEquals(0, ContentDirectoryClient.parseResult(didl).size)
    }

    /** 只给无前缀标签（部分老设备不写 dc:/upnp: 前缀） */
    @Test
    fun parseResult_noNamespacePrefixes() {
        val didl = """
            <DIDL-Lite>
              <container id="5">
                <title>Music</title>
              </container>
              <item id="6">
                <title>a.mp3</title>
                <class>object.item.audioItem</class>
                <res protocolInfo="http-get:*:audio/mpeg:*">http://x/a.mp3</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
        val objects = ContentDirectoryClient.parseResult(didl)
        assertEquals(2, objects.size)
        assertEquals("Music", (objects[0] as MediaContainer).title)
        assertEquals("http://x/a.mp3", (objects[1] as MediaItem).resUrl)
    }
}
