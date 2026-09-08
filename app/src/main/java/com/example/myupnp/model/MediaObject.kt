package com.example.myupnp.model

/**
 * MediaServer 曲库里的一个"东西"
 * ------------------------------------------------------------------
 * MediaServer(ContentDirectory 服务)用一棵树组织媒体：
 *  - container（容器）  = 文件夹，如"音乐 / 流行 / 周杰伦"
 *  - item（条目）       = 单个媒体文件，如某首 mp3（带 res 播放地址）
 * Browse 一次只返回某一层的直接子对象。
 */
sealed class MediaObject {
    /** 对象 ID：下次 Browse 或 GetItem 的 ObjectID（容器用它进入下一层） */
    abstract val id: String
    abstract val title: String

    /** 是否容器（文件夹） */
    open val isContainer: Boolean get() = false

    /** 显示用的一行文本（供列表 UI） */
    open fun displayText(): String = title
}

/** 文件夹：点进去 = 用它的 id 再 Browse 一次 */
data class MediaContainer(
    override val id: String,
    override val title: String,
    val childCount: Int = 0,
    val upnpClass: String = ""
) : MediaObject() {
    override val isContainer: Boolean = true
    override fun displayText(): String =
        "📁 $title" + if (childCount > 0) "  ($childCount)" else ""
}

/** 媒体条目：可播放的文件（res = 实际媒体 URL） */
data class MediaItem(
    override val id: String,
    override val title: String,
    val upnpClass: String = "",
    /** res 元素里的实际资源地址，给播放器 SetAVTransportURI 用 */
    val resUrl: String = "",
    /** res 的 MIME，如 audio/mpeg */
    val mime: String = "",
    /** 第 7 课 C：歌手（upnp:artist / dc:creator） */
    val artist: String = "",
    /** 第 7 课 C：专辑（upnp:album） */
    val album: String = "",
    /** 第 7 课 C：专辑封面地址（upnp:albumArtURI，相对路径已绝对化） */
    val artUrl: String = ""
) : MediaObject() {
    override fun displayText(): String {
        val icon = when {
            upnpClass.contains("audioItem") -> "🎵"
            upnpClass.contains("videoItem") -> "🎬"
            upnpClass.contains("imageItem") -> "🖼️"
            else -> "📄"
        }
        // 第 7 课 C：曲库行尾带歌手，选歌更方便
        val artistSuffix = if (artist.isNotBlank()) "  — $artist" else ""
        return "$icon $title$artistSuffix"
    }
}
