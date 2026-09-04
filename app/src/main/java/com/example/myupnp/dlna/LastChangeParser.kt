package com.example.myupnp.dlna

/**
 * AVTransport 事件里的 LastChange 展开解析
 * ------------------------------------------------------------------
 * 第 3 课遗留问题：DLNA 设备（尤其 AVTransport）推送事件时，几乎总是
 * 只给一个 LastChange 变量，值是一整段"转义过的 XML"。形如：

   <LastChange xmlns="urn:schemas-upnp-org:service:AVTransport:1">
     &lt;Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"&gt;
       &lt;InstanceID val="0"&gt;
         &lt;TransportState val="PLAYING"/&gt;              ← 播放状态
         &lt;CurrentTrackDuration val="0:03:45"/&gt;         ← 总时长
         &lt;RelativeTimePosition val="0:00:12"/&gt;         ← 已播位置
       &lt;/InstanceID&gt;
     &lt;/Event&gt;
   </LastChange>

 * 本解析器负责把"解开一层转义后的内部 XML"（上面 &lt; 变成 < 之后）再解析，
 * 提取出所有 val="xxx" 属性 -> (状态名, 值) 字典。
 * 纯 JVM 实现，便于单测。
 */
object LastChangeParser {

    /** 解析结果：instanceId + 状态变量字典 */
    data class Result(
        val instanceId: String?,
        val values: Map<String, String>
    ) {
        /** 播放状态：PLAYING / PAUSED / STOPPED / NO_MEDIA_PRESENT … */
        val transportState: String? get() = values["TransportState"]
        val currentTrackDuration: String? get() = values["CurrentTrackDuration"]
        val relativeTimePosition: String? get() = values["RelativeTimePosition"]
        val absoluteTimePosition: String? get() = values["AbsoluteTimePosition"]
        val volume: String? get() = values["Volume"]      // RenderingControl 的 LastChange
        val mute: String? get() = values["Mute"]
    }

    fun parse(lastChangeXml: String): Result {
        var instanceId: String? = null
        val values = LinkedHashMap<String, String>()

        // 每个 val="..." 属性所在的标签：<TagName  ...  val="值"
        val re = Regex("<([A-Za-z0-9_.:\\-]+)\\b([^>]*?)\\bval=\"([^\"]*)\"")
        for (m in re.findAll(lastChangeXml)) {
            val tag = m.groupValues[1]
            val value = m.groupValues[3]
            if (tag.equals("Event", ignoreCase = true)) continue
            if (tag.equals("InstanceID", ignoreCase = true)) {
                instanceId = value
                continue
            }
            values[tag] = value
        }
        return Result(instanceId, values)
    }
}
