package com.example.myupnp

import com.example.myupnp.dlna.DlnaPlayer
import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.model.UpnpService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 4 课单元测试：DlnaPlayer 的识别/DIDL 元数据/GetVolume 结果解析（纯逻辑部分）。
 * 网络动作链（SetAVTransportURI->Play）的正确性由 SoapCallerTest 的假设备保证，
 * 这里把不依赖网络的编排逻辑钉死。
 */
class DlnaPlayerTest {

    private fun rendererDevice() = UpnpDevice(
        friendlyName = "Test TV",
        deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1",
        services = listOf(
            UpnpService(
                serviceType = DlnaPlayer.URN_AVTRANSPORT,
                serviceId = "urn:upnp-org:serviceId:AVTransport",
                controlUrl = "http://192.168.1.10:8200/upnp/control/AVTransport"
            ),
            UpnpService(
                serviceType = DlnaPlayer.URN_RENDERING,
                serviceId = "urn:upnp-org:serviceId:RenderingControl",
                controlUrl = "http://192.168.1.10:8200/upnp/control/RenderingControl"
            )
        )
    )

    @Test
    fun isRenderer_detectsAvTransport() {
        assertTrue(DlnaPlayer.isRenderer(rendererDevice()))

        // 没有 AVTransport 的不是播放器（例如 MediaServer）
        val server = UpnpDevice(services = listOf(
            UpnpService(serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1")
        ))
        assertFalse(DlnaPlayer.isRenderer(server))
    }

    @Test
    fun findsAvTransportAndRenderingControl() {
        val device = rendererDevice()
        assertNotNull(DlnaPlayer.avTransportOf(device))
        assertEquals(
            "http://192.168.1.10:8200/upnp/control/AVTransport",
            DlnaPlayer.avTransportOf(device)!!.controlUrl
        )
        assertNotNull(DlnaPlayer.renderingControlOf(device))
        // 服务器设备两服务都找不到
        assertNull(DlnaPlayer.avTransportOf(
            UpnpDevice(services = listOf(UpnpService(serviceType = "urn:x:ContentDirectory:1")))
        ))
    }

    @Test
    fun didlMetadata_containsTitleUrlAndClass() {
        val didl = DlnaPlayer.didlMetadata("http://192.168.1.5/a.mp4", "Demo & Test")
        assertTrue(didl.contains("<dc:title>Demo &amp; Test</dc:title>")) // XML 转义
        assertTrue(didl.contains("http://192.168.1.5/a.mp4"))
        assertTrue(didl.contains("object.item.videoItem"))
    }

    @Test
    fun getVolume_parsesOutParameter() {
        // GetVolume 的 out 参数（CurrentVolume）藏在 SOAP 响应里，验证解析
        val body = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                  <CurrentVolume>45</CurrentVolume>
                </u:GetVolumeResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertEquals(45, DlnaPlayer.parseCurrentVolume(body))
        // 没有该 out 参数时返回 -1（容错）
        assertEquals(-1, DlnaPlayer.parseCurrentVolume("<s:Body><u:X/></s:Body>"))
    }
}
