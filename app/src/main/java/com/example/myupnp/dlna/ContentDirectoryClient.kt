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
        return toBrowseResult(result, service.controlUrl)
    }

    // ------------------------------------------------------------------
    // 服务端搜索（Search 动作，可选能力，取决于设备实现）
    // ------------------------------------------------------------------

    /**
     * 在服务器上搜索（ContentDirectory:Search）。
     * Search 属于可选动作：不少设备不支持或只认部分语法。调用方应先查
     * SCPD 是否声明了 Search；失败时可用 [useLike] 再试一次。
     *
     * @param useLike false 用 `contains`（较通用）；true 用 `like "%kw%"`（部分服务器才认）
     */
    fun search(
        service: com.example.myupnp.model.UpnpService,
        keyword: String,
        containerId: String = ROOT_OBJECT_ID,
        start: Int = 0,
        count: Int = 0,
        useLike: Boolean = false
    ): BrowseResult {
        val kw = keyword.replace("\"", "").trim()
        if (kw.isEmpty()) return BrowseResult(objects = emptyList())
        val criteria = if (useLike) {
            """(dc:title like "%$kw%") or (upnp:artist like "%$kw%") or (upnp:album like "%$kw%")"""
        } else {
            """(dc:title contains "$kw") or (upnp:artist contains "$kw") or (upnp:album contains "$kw")"""
        }
        val result = SoapCaller.call(
            controlUrl = service.controlUrl,
            serviceType = service.serviceType,
            actionName = "Search",
            args = mapOf(
                "ContainerID" to containerId,
                "SearchCriteria" to criteria,
                "Filter" to "*",
                "StartingIndex" to start.toString(),
                "RequestedCount" to count.toString(),
                "SortCriteria" to ""
            )
        )
        return toBrowseResult(result, service.controlUrl)
    }

    /** SOAP 结果 -> BrowseResult（Browse 与 Search 共用同一套 DIDL 解析） */
    private fun toBrowseResult(result: SoapCaller.SoapResult, baseUrl: String): BrowseResult {
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
        return BrowseResult(objects = parseResult(didl, baseUrl), soapHttp = result.httpCode)
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
     * @param baseUrl ContentDirectory 的 controlUrl：用于把 albumArtURI 相对路径转绝对
     */
    internal fun parseResult(didl: String, baseUrl: String = ""): List<MediaObject> {
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
                    resUrl = res.url,
                    mime = res.mime,
                    // 第 7 课 C：歌手/专辑/封面（有的设备用 dc:creator 当歌手）
                    artist = extractArtist(inner),
                    album = extractElementText(inner, "album"),
                    artUrl = absoluteUrl(
                        raw = extractElementText(inner, "albumArtURI"),
                        base = baseUrl
                    ),
                    // 第 8 课：文件大小/时长 —— 用于"同一文件多 URL"时的去重
                    sizeBytes = res.sizeBytes,
                    durationSec = res.durationSec
                )
            )
        }
        return result
    }

    /** res 节点的解析结果 */
    private data class ResInfo(
        val url: String = "",
        val mime: String = "",
        val sizeBytes: Long = 0,
        val durationSec: Long = 0
    )

    /** 解析 <res> 节点：URL / mime / size / duration（属性缺失即 0） */
    private fun extractRes(inner: String): ResInfo {
        val m = Regex("<(?:[\\w.]+:)?res\\b([^>]*)>(.*?)</(?:[\\w.]+:)?res>", RegexOption.DOT_MATCHES_ALL)
            .find(inner) ?: return ResInfo()
        val attrs = m.groupValues[1]
        val url = m.groupValues[2].trim()
        val proto = Regex("protocolInfo\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
        val mime = proto?.split(':')?.getOrNull(2)?.trim() ?: ""
        val size = Regex("size\\s*=\\s*\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val durationRaw = Regex("duration\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
        return ResInfo(url = url, mime = mime, sizeBytes = size, durationSec = parseDuration(durationRaw))
    }

    /** "0:04:12.345" / "H:MM:SS" -> 秒；解析不了就 0 */
    private fun parseDuration(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.substringBefore('.')
        val parts = clean.split(':')
        return try {
            when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                else -> 0L
            }
        } catch (_: NumberFormatException) {
            0L
        }
    }

    /** 歌手：upnp:artist 优先，没有就退回 dc:creator（个别设备命名习惯） */
    private fun extractArtist(inner: String): String {
        val artist = extractElementText(inner, "artist")
        if (artist.isNotEmpty()) return artist
        val creator = Regex(
            "<(?:[\\w.]+:)?creator\\b[^>]*>(.*?)</(?:[\\w.]+:)?creator>",
            RegexOption.DOT_MATCHES_ALL
        ).find(inner)
        return creator?.groupValues?.get(1)?.trim().orEmpty()
    }

    /**
     * 相对地址转绝对：很多 MediaServer 的 albumArtURI 只给路径（如 /art/1.jpg），
     * 以 ContentDirectory 的 controlUrl 为基准拼全（协议+主机+端口保持一致）。
     */
    private fun absoluteUrl(raw: String, base: String): String {
        val raw = raw.trim()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return runCatching {
            val url = java.net.URL(java.net.URL(base), raw)
            if (url.protocol == "http" || url.protocol == "https") url.toString() else ""
        }.getOrDefault("")
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

    private fun extractInt(inner: String, localName: String): Int =
        Regex("<(?:[\\w.]+:)?$localName\\b[^>]*>(.*?)</(?:[\\w.]+:)?$localName>", RegexOption.DOT_MATCHES_ALL)
            .find(inner)?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: 0
}
