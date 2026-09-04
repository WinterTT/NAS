package com.example.myupnp

import com.example.myupnp.dlna.LastChangeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第 4 课单元测试：LastChange 展开解析。
 * DLNA 播放器把播放进度/状态打包进一个 LastChange 变量，
 * 本测试验证能把它解出 TransportState / 进度 / 音量等字段。
 */
class LastChangeParserTest {

    /** 典型 AVTransport 事件（RenderingControl 的 Volume/Mute 也会混进来） */
    private val avtXml = """
        <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
          <InstanceID val="0">
            <TransportState val="PLAYING"/>
            <TransportStatus val="OK"/>
            <CurrentTrackDuration val="0:03:45"/>
            <RelativeTimePosition val="0:00:12"/>
            <CurrentPlayMode val="NORMAL"/>
          </InstanceID>
        </Event>
    """.trimIndent()

    @Test
    fun parse_avtExtractsPlaybackState() {
        val r = LastChangeParser.parse(avtXml)
        assertEquals("0", r.instanceId)
        assertEquals("PLAYING", r.transportState)
        assertEquals("0:03:45", r.currentTrackDuration)
        assertEquals("0:00:12", r.relativeTimePosition)
        // 未出现的字段为 null
        assertNull(r.absoluteTimePosition)
    }

    @Test
    fun parse_renderingControlExtractsVolumeAndMute() {
        val rcsXml = """
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/">
              <InstanceID val="0">
                <Volume channel="Master" val="45"/>
                <Mute channel="Master" val="0"/>
              </InstanceID>
            </Event>
        """.trimIndent()
        val r = LastChangeParser.parse(rcsXml)
        assertEquals("45", r.volume)
        assertEquals("0", r.mute)
    }

    @Test
    fun parse_handlesRepeatedEventTags() {
        // 部分设备把 AVT 和 RCS 的 Event 拼在一个 LastChange 里
        val combined = avtXml + """
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/">
              <InstanceID val="0"><Volume channel="Master" val="78"/></InstanceID>
            </Event>
        """.trimIndent()
        val r = LastChangeParser.parse(combined)
        assertEquals("PLAYING", r.transportState)
        assertEquals("78", r.volume)
    }
}
