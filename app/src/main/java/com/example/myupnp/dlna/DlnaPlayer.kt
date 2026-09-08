package com.example.myupnp.dlna

import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.model.UpnpService
import com.example.myupnp.soap.SoapCaller

/**
 * DLNA 播放器场景编排（第 4 课）
 * ------------------------------------------------------------------
 * 前面三课学会了 发现(SSDP)/描述(Description)/控制(SOAP)/事件(GENA)。
 * 这一课把它们串成一个真实场景：**把手机上一个媒体 URL 推给电视播放**。
 *
 * DLNA 播放器的标准动作链（服务类型是 AVTransport）：
 *   1) SetAVTransportURI(InstanceID=0, CurrentURI=<媒体地址>, CurrentURIMetaData=<DIDL 描述>)
 *        -> 告诉播放器"播这个资源"
 *   2) Play(InstanceID=0, Speed=1)
 *        -> 开始播放
 *   之后可用 Pause / Stop / Seek 控制，音量走 RenderingControl 服务。
 *
 * 本类只做"编排"：识别服务、构造参数、把动作发给正确服务。
 * 网络细节仍由 SoapCaller 负责。标注为同步阻塞，调用方请放后台线程。
 */
object DlnaPlayer {

    const val URN_AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val URN_RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val INSTANCE_ID = "0"

    // ------------------------------------------------------------------
    // 服务识别（纯逻辑，无网络）
    // ------------------------------------------------------------------

    /** 设备是不是"播放器"：有 AVTransport 服务的通常就是 MediaRenderer */
    fun isRenderer(device: UpnpDevice): Boolean = avTransportOf(device) != null

    fun avTransportOf(device: UpnpDevice): UpnpService? =
        device.services.firstOrNull { it.serviceType.contains("AVTransport") }

    fun renderingControlOf(device: UpnpDevice): UpnpService? =
        device.services.firstOrNull { it.serviceType.contains("RenderingControl") }

    // ------------------------------------------------------------------
    // 播放主流程（同步阻塞，需在后台线程调用）
    // ------------------------------------------------------------------

    /**
     * "推送并播放"：SetAVTransportURI + Play 两连击。
     * @return (动作名, 结果) 列表，便于调用方逐步打日志
     */
    fun pushAndPlay(
        renderer: UpnpService,
        mediaUrl: String,
        title: String = "MyUPNP"
    ): List<Pair<String, SoapCaller.SoapResult>> {
        val results = mutableListOf<Pair<String, SoapCaller.SoapResult>>()

        // 第一步：设定播放源。CurrentURIMetaData 是 DIDL 描述，很多电视缺了会拒播
        val setUri = SoapCaller.call(
            controlUrl = renderer.controlUrl,
            serviceType = renderer.serviceType,
            actionName = "SetAVTransportURI",
            args = mapOf(
                "InstanceID" to INSTANCE_ID,
                "CurrentURI" to mediaUrl,
                "CurrentURIMetaData" to didlMetadata(mediaUrl, title)
            )
        )
        results += "SetAVTransportURI" to setUri
        if (!setUri.success) return results // 源没设上就别播了

        // 第二步：播放（DLNA 标准还要 Speed=1）
        val play = SoapCaller.call(
            controlUrl = renderer.controlUrl,
            serviceType = renderer.serviceType,
            actionName = "Play",
            args = mapOf("InstanceID" to INSTANCE_ID, "Speed" to "1")
        )
        results += "Play" to play
        return results
    }

    /** 简单的传输动作：Pause / Stop / Next / Previous（只要 InstanceID） */
    fun transportAction(renderer: UpnpService, action: String): SoapCaller.SoapResult =
        SoapCaller.call(
            controlUrl = renderer.controlUrl,
            serviceType = renderer.serviceType,
            actionName = action,
            args = mapOf("InstanceID" to INSTANCE_ID)
        )

    /** 音量+/-（RenderingControl） */
    fun stepVolume(rc: UpnpService, delta: Int): SoapCaller.SoapResult {
        // 先读当前音量（GetVolume 的 out 参数是 CurrentVolume）
        val current = getVolume(rc)
        val target = (current + delta).coerceIn(0, 100)
        return SoapCaller.call(
            controlUrl = rc.controlUrl,
            serviceType = rc.serviceType,
            actionName = "SetVolume",
            args = mapOf(
                "InstanceID" to INSTANCE_ID,
                "Channel" to "Master",
                "DesiredVolume" to target.toString()
            )
        )
    }

    /** 直接设音量到指定值（RenderingControl，音量滑杆用） */
    fun setVolume(rc: UpnpService, volume: Int): SoapCaller.SoapResult {
        return SoapCaller.call(
            controlUrl = rc.controlUrl,
            serviceType = rc.serviceType,
            actionName = "SetVolume",
            args = mapOf(
                "InstanceID" to INSTANCE_ID,
                "Channel" to "Master",
                "DesiredVolume" to volume.coerceIn(0, 100).toString()
            )
        )
    }

    /** 读当前音量；读不到返回 -1 */
    fun getVolume(rc: UpnpService): Int {
        val r = SoapCaller.call(
            controlUrl = rc.controlUrl,
            serviceType = rc.serviceType,
            actionName = "GetVolume",
            args = mapOf("InstanceID" to INSTANCE_ID, "Channel" to "Master")
        )
        if (!r.success) return -1
        return parseCurrentVolume(r.body)
    }

    /**
     * 从 GetVolume 的 SOAP 响应里解析 out 参数 CurrentVolume。
     * 响应的 out 参数形如：<CurrentVolume>45</CurrentVolume>
     * （纯逻辑，便于单测）
     */
    fun parseCurrentVolume(soapBody: String): Int =
        Regex("<CurrentVolume>\\s*(\\d+)\\s*</CurrentVolume>")
            .find(soapBody)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    // ------------------------------------------------------------------
    // DIDL 元数据（纯逻辑，无网络）
    // ------------------------------------------------------------------

    /**
     * 构造 DLNA 的 DIDL-Lite 描述。SetAVTransportURI 的 CurrentURIMetaData
     * 期望一个 DIDL 文档，声明资源的类型/标题，很多 DLNA 设备缺它就直接 701/712。
     * 会根据 URL 扩展名推断音/视频，默认按音频处理（音箱场景）。
     */
    fun didlMetadata(url: String, title: String): String {
        val kind = guessMediaKind(url)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:class>${kind.upnpClass}</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${kind.mime}:*\">${xmlEscape(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    /** URL 扩展名 → (MIME, UPnP class) */
    private data class MediaKind(val mime: String, val upnpClass: String)

    private fun guessMediaKind(url: String): MediaKind {
        val clean = url.substringBefore('?') // 去掉查询参数
        val ext = clean.substringAfterLast('.', "").lowercase()
        return when (ext) {
            // 音频：DLNA 音箱主要吃 mp3；wav/flac/m4a 很多也支持
            "mp3" -> MediaKind("audio/mpeg", "object.item.audioItem")
            "wav" -> MediaKind("audio/wav", "object.item.audioItem")
            "flac" -> MediaKind("audio/flac", "object.item.audioItem")
            "m4a", "aac" -> MediaKind("audio/mp4", "object.item.audioItem")
            "ogg", "oga" -> MediaKind("audio/ogg", "object.item.audioItem")
            // 视频
            "mp4", "m4v", "mov" -> MediaKind("video/mp4", "object.item.videoItem")
            "mkv" -> MediaKind("video/x-matroska", "object.item.videoItem")
            "avi" -> MediaKind("video/x-msvideo", "object.item.videoItem")
            "ts", "m2ts" -> MediaKind("video/mpeg", "object.item.videoItem")
            // 图片
            "jpg", "jpeg", "png" -> MediaKind("image/jpeg", "object.item.imageItem")
            // 未知一律按音频处理（本场景以音箱为主）
            else -> MediaKind("audio/mpeg", "object.item.audioItem")
        }
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
