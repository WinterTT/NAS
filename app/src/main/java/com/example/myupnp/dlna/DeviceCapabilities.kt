package com.example.myupnp.dlna

import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.model.UpnpService
import com.example.myupnp.soap.SoapCaller

/**
 * 渲染器能力探测（第 12 课：推送前就知道设备支持什么）
 * ------------------------------------------------------------------
 * 标准做法：MediaRenderer 通常带 ConnectionManager 服务，调它的
 *   GetProtocolInfo
 * 会返回三串逗号分隔的 protocolInfo：
 *   SourceProtocolInfo 它能"提供"什么（一般用不上）
 *   SinkProtocolInfo   它能"接收/播放"什么  ← 我们要的
 *   CurrentConnectionIDs
 * 每条形如：http-get:*:audio/mpeg:* 或 http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG
 * 第 3 段是 MIME → 据此判定 音乐/视频/图片 支持情况。
 *
 * 设备没实现该动作时不能瞎猜，返回"未知"（supported=false + note）。
 */
object DeviceCapabilities {

    data class Capabilities(
        val audio: Boolean = false,
        val video: Boolean = false,
        val image: Boolean = false,
        val sinkProtocols: List<String> = emptyList(),
        /** 是否成功拿到并解析（false = 未知，不能据此拦截） */
        val supported: Boolean = false,
        val note: String = ""
    ) {
        /** true/false = 明确支持/不支持；null = 未知 */
        fun supports(kind: MediaKind): Boolean? = when {
            !supported -> null
            kind == MediaKind.AUDIO -> audio
            kind == MediaKind.VIDEO -> video
            kind == MediaKind.IMAGE -> image
            else -> null
        }

        /** 展示用：音乐/视频/图片 */
        fun label(): String = when {
            !supported -> "支持格式未知"
            else -> buildList {
                if (audio) add("音乐")
                if (video) add("视频")
                if (image) add("图片")
            }.joinToString(" / ").ifEmpty { "未声明可播放格式" }
        }

        /** 紧凑图标（列表里用） */
        fun icons(): String = when {
            !supported -> ""
            else -> buildString {
                if (audio) append("🎵")
                if (video) append("🎬")
                if (image) append("🖼")
            }
        }
    }

    /** 查询一台渲染器支持接收的媒体类型；失败返回"未知" */
    fun query(device: UpnpDevice?): Capabilities {
        if (device == null) return Capabilities(note = "设备信息未就绪")
        val cm = connectionManagerOf(device)
            ?: return Capabilities(note = "该设备没有 ConnectionManager 服务，无法探测")
        val result = runCatching {
            SoapCaller.call(
                controlUrl = cm.controlUrl,
                serviceType = cm.serviceType,
                actionName = "GetProtocolInfo",
                args = emptyMap()
            )
        }.getOrElse { return Capabilities(note = "GetProtocolInfo 异常: ${it.message}") }

        if (!result.success) {
            return Capabilities(note = "GetProtocolInfo 失败（设备可能未实现）: ${result.summary()}")
        }
        val sink = Regex(
            "<SinkProtocolInfo>\\s*(.*?)\\s*</SinkProtocolInfo>",
            RegexOption.DOT_MATCHES_ALL
        ).find(result.body)?.groupValues?.get(1).orEmpty()
        if (sink.isBlank()) return Capabilities(note = "设备没有声明可接收的格式")

        val protocols = sink.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var audio = false
        var video = false
        var image = false
        for (p in protocols) {
            val parts = p.split(':')
            val mime = parts.getOrNull(2)?.lowercase().orEmpty()
            val extra = parts.getOrNull(3)?.lowercase().orEmpty()
            when {
                mime.startsWith("audio/") -> audio = true
                mime.startsWith("video/") -> video = true
                mime.startsWith("image/") -> image = true
                // 有些设备用 application/* 表示音频（ogg/octet-stream + PN 提示）
                mime.startsWith("application/") && (
                    extra.contains("mp3") || extra.contains("mpeg") || extra.contains("ogg") ||
                        extra.contains("flac") || extra.contains("wav")
                    ) -> audio = true
                else -> Unit
            }
            // DLNA profile 也能补充判断图片
            if (extra.contains("jpeg") || extra.contains("png") || extra.contains("gif")) image = true
        }
        return Capabilities(
            audio = audio,
            video = video,
            image = image,
            sinkProtocols = protocols,
            supported = true
        )
    }

    fun connectionManagerOf(device: UpnpDevice): UpnpService? =
        device.services.firstOrNull { it.serviceType.contains("ConnectionManager") }
}

/** 媒体类型（用于能力匹配） */
enum class MediaKind { AUDIO, VIDEO, IMAGE, UNKNOWN }

/** 从 MIME / upnp:class / 扩展名判断媒体类型 */
object MediaKinds {

    fun of(mime: String, upnpClass: String, url: String): MediaKind {
        val m = mime.lowercase()
        val c = upnpClass.lowercase()
        val path = url.substringBefore('?').lowercase()
        return when {
            c.contains("imageitem") || m.startsWith("image/") ||
                path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".bmp") ->
                MediaKind.IMAGE

            c.contains("videoitem") || m.startsWith("video/") ||
                path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".mov") ||
                path.endsWith(".avi") || path.endsWith(".webm") || path.endsWith(".3gp") ->
                MediaKind.VIDEO

            c.contains("audioitem") || m.startsWith("audio/") ||
                path.endsWith(".mp3") || path.endsWith(".flac") || path.endsWith(".wav") ||
                path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".ogg") ->
                MediaKind.AUDIO

            else -> MediaKind.UNKNOWN
        }
    }

    fun label(kind: MediaKind): String = when (kind) {
        MediaKind.AUDIO -> "音乐"
        MediaKind.VIDEO -> "视频"
        MediaKind.IMAGE -> "图片"
        MediaKind.UNKNOWN -> "该文件"
    }
}
