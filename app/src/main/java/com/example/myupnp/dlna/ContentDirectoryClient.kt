package com.example.myupnp.dlna

import com.example.myupnp.model.MediaContainer
import com.example.myupnp.model.MediaItem
import com.example.myupnp.model.MediaObject
import com.example.myupnp.soap.SoapCaller

/**
 * ContentDirectory（曲库）客户端 —— DLNA MediaServer 的内容浏览
 * ------------------------------------------------------------------
 * MediaServer 的价值：控制点不用手输 URL，而是"逛"它的曲库挑歌。
 * 逛的方式是调 ContentDirectory 服务的 Browse 动作：

   Browse(
     ObjectID        = "0",                  <- 根；容器 ID 即其 ObjectID
     BrowseFlag      = "BrowseDirectChildren",// 只要直接子项（一层）
     Filter          = "*",                   // 要全部元数据字段
     StartingIndex   = "0",
     RequestedCount  = "0",                   // 0 = 全部返回
     SortCriteria    = ""
   )

 * 响应里最重要的字段是 Result：一段 DIDL-Lite XML（转义后塞在 SOAP 里），
 * 里面每个 <container> = 文件夹，每个 <item> = 媒体文件（带 res 播放地址）。
 * 本类负责：调用 Browse -> 解出 Result -> 解析 DIDL-Lite -> List<MediaObject>
 *
 * 纯网络部分在 SoapCaller；解析部分拆成 parseResult() 便于纯 JVM 单测。
 */
object ContentDirectoryClient {

    const val URN = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val ROOT_OBJECT_ID = "0"

    /**
     * Browse 的结果（带诊断信息，方便真机排查设备怪癖）
     * @param objects  解析出的对象；失败时为空
     * @param ok       是否成功
     * @param error    失败原因（SOAP 错误 / 找不到 Result / 响应片段）
     */
    data class BrowseResult(
        val objects: List<MediaObject> = emptyList(),
        val ok: Boolean = true,
        val error: String = "",
        val soapHttp: Int = 0,
        val rawSnippet: String = "" // 原始 SOAP 响应片段，排查用
    )

    /**
     * 浏览某一层目录（同步阻塞，需后台线程调用）。
     * @param objectId "0" = 根；进文件夹就传那个容器的 id
     */
    fun browse(service: com.example.myupnp.model.UpnpService, objectId: String = ROOT_OBJECT_ID): BrowseResult {
        val result = SoapCaller.call(
            controlUrl = service.controlUrl,
            serviceType = service.serviceType,
            actionName = "Browse",
            args = mapOf(
                "ObjectID" to objectId,
                "BrowseFlag" to "BrowseDirectChildren",
                "Filter" to "*",
                "StartingIndex" to "0",
                "RequestedCount" to "0",
                "SortCriteria" to ""
            )
        )
        if (!result.success) {
            return BrowseResult(
                ok = false,
                error = result.summary(),
                soapHttp = result.httpCode,
                rawSnippet = result.body.take(400)
            )
        }
        val didl = extractResult(result.body)
        if (didl == null) {
            // HTTP 成功但没有 <Result>：多半是格式特殊（属性/大小写/结构不同）
            return BrowseResult(
                ok = false,
                error = "SOAP 成功但响应体里找不到 <Result>（见原始片段）",
                soapHttp = result.httpCode,
                rawSnippet = result.body.take(600)
            )
        }
        return BrowseResult(objects = parseResult(didl))
    }

    // ------------------------------------------------------------------
    // Result 抽取：SOAP 响应里 <Result> 是实体转义过的 DIDL
    // ------------------------------------------------------------------

    /** 从 SOAP 响应体里抠出 <Result> 内容并反转 XML 转义（兼容属性/命名空间/大小写） */
    internal fun extractResult(soapBody: String): String? {
        // 有的设备写 <Result>，有的带 xmlns 属性，还有极少数大写 <RESULT>。
        // 用 [^>]* 容忍属性；标签名大小写都试一遍。
        val candidates = listOf(
            Regex("<Result[^>]*>(.*?)</Result[^>]*>", RegexOption.DOT_MATCHES_ALL),
            Regex("<result[^>]*>(.*?)</result[^>]*>", RegexOption.DOT_MATCHES_ALL)
        )
        for (re in candidates) {
            val m = re.find(soapBody)
            if (m != null) return unescape(m.groupValues[1])
        }
        // 自闭合 <Result/> = 空目录的合法表示
        if (Regex("<Result[^>]*/>").containsMatchIn(soapBody)) return ""
        return null
    }

    /** 反转 XML 实体转义 */
    internal fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    // ------------------------------------------------------------------
    // DIDL-Lite 解析（纯逻辑，无网络，JVM 可测）
    // ------------------------------------------------------------------

    /**
     * 把 Result 里的一段 DIDL-Lite 解析成 MediaObject 列表。
     * DIDL-Lite 结构（扁平一层）：
     *   <container id="2" parentID="0" restricted="1">
     *     <dc:title>流行</dc:title>
     *     <upnp:class>object.container.storageFolder</upnp:class>
     *   </container>
     *   <item id="12" parentID="2" restricted="1">
     *     <dc:title>晴天.mp3</dc:title>
     *     <upnp:class>object.item.audioItem.musicTrack</upnp:class>
     *     <res protocolInfo="http-get:*:audio/mpeg:...">http://ip:port/file.mp3</res>
     *   </item>
     */
    internal fun parseResult(didl: String): List<MediaObject> {
        val result = ArrayList<MediaObject>()

        // 容器（可能带命名空间前缀，如 <container> 或 <DIDL-Lite:container>）
        val containerRe = Regex(
            "<(?:[\\w.]+:)?container\\b[^>]*\\bid=\"([^\"]*)\"[^>]*>(.*?)</(?:[\\w.]+:)?container>",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in containerRe.findAll(didl)) {
            val id = m.groupValues[1]
            val inner = m.groupValues[2]
            result.add(
                MediaContainer(
                    id = id,
                    title = extractTitle(inner),
                    childCount = extractInt(inner, "childCount"),
                    upnpClass = extractElementText(inner, "class")
                )
            )
        }

        // 条目
        val itemRe = Regex(
            "<(?:[\\w.]+:)?item\\b[^>]*\\bid=\"([^\"]*)\"[^>]*>(.*?)</(?:[\\w.]+:)?item>",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in itemRe.findAll(didl)) {
            val id = m.groupValues[1]
            val inner = m.groupValues[2]
            val res = extractRes(inner)
            result.add(
                MediaItem(
                    id = id,
                    title = extractTitle(inner),
                    upnpClass = extractElementText(inner, "class"),
                    resUrl = res.first,
                    mime = res.second
                )
            )
        }
        return result
    }

    /** dc:title 或 title（设备命名习惯不一），取第一个非空 */
    private fun extractTitle(inner: String): String {
        val m = Regex("<(?:[\\w.]+:)?title[^>]*>(.*?)</(?:[\\w.]+:)?title>", RegexOption.DOT_MATCHES_ALL)
            .find(inner)
        return m?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 取 <upnp:class> 文本（忽略 xmlns 等属性差异） */
    private fun extractElementText(inner: String, localName: String): String {
        val m = Regex("<(?:[\\w.]+:)?$localName\\b[^>]*>(.*?)</(?:[\\w.]+:)?$localName>", RegexOption.DOT_MATCHES_ALL)
            .find(inner)
        return m?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 解析 <res> 节点：(URL, mime)；protocolInfo 形如 http-get:*:audio/mpeg:DLNA... */
    private fun extractRes(inner: String): Pair<String, String> {
        val m = Regex("<(?:[\\w.]+:)?res\\b([^>]*)>(.*?)</(?:[\\w.]+:)?res>", RegexOption.DOT_MATCHES_ALL)
            .find(inner) ?: return "" to ""
        val attrs = m.groupValues[1]
        val url = m.groupValues[2].trim()
        val proto = Regex("protocolInfo\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
        val mime = proto?.split(':')?.getOrNull(2)?.trim() ?: ""
        return url to mime
    }

    private fun extractInt(inner: String, localName: String): Int =
        Regex("<(?:[\\w.]+:)?$localName\\b[^>]*>(.*?)</(?:[\\w.]+:)?$localName>", RegexOption.DOT_MATCHES_ALL)
            .find(inner)?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: 0
}
