package com.example.myupnp.ssdp

/**
 * 一条 SSDP 报文（UDP 文本，基于 HTTP/1.1 风格的首部）
 * ------------------------------------------------------------------
 * SSDP 只有两种我们关心的报文：
 *
 *  1. M-SEARCH（控制点发出，组播到 239.255.255.250:1900，问"谁在？"）
 *     M-SEARCH * HTTP/1.1\r\n
 *     HOST: 239.255.255.250:1900\r\n
 *     MAN: "ssdp:discover"\r\n
 *     MX: 2\r\n                      <- 最多等几秒回复
 *     ST: ssdp:all\r\n               <- 搜索目标，ssdp:all=所有设备
 *
 *  2. HTTP/1.1 200 OK（设备回复，单播给控制点）
 *     HTTP/1.1 200 OK\r\n
 *     CACHE-CONTROL: max-age=1800\r\n
 *     LOCATION: http://192.168.1.10:8200/rootDesc.xml\r\n  <- 设备描述地址！
 *     SERVER: ...\r\n
 *     ST: upnp:rootdevice\r\n
 *     USN: uuid:...::upnp:rootdevice\r\n                  <- 设备唯一标识
 *
 *  3. NOTIFY（设备主动广播：上线 ssdp:alive / 下线 ssdp:byebye）
 *     NOTIFY * HTTP/1.1\r\n
 *     NTS: ssdp:alive\r\n
 *     NT: upnp:rootdevice\r\n
 *     LOCATION: ...\r\n
 *     USN: ...
 */
enum class SsdpMessageType {
    /** 对我们 M-SEARCH 的应答：设备在线，且告诉我们 LOCATION */
    SEARCH_RESPONSE,

    /** 设备主动广播"我上线了" */
    NOTIFY_ALIVE,

    /** 设备主动广播"我下线了" */
    NOTIFY_BYEBYE,

    /** 其它（例如别的控制点发的 M-SEARCH），可以忽略 */
    OTHER
}

/**
 * 解析后的 SSDP 消息。
 * @param headers 首部字段，key 统一转小写，方便取用
 */
data class SsdpMessage(
    val type: SsdpMessageType,
    val sourceHost: String,
    val headers: Map<String, String>,
    val raw: String
) {

    /** 设备描述文档地址（绝大多数消息的关键字段） */
    val location: String? get() = headers["location"]

    /** 设备/服务唯一名称，如 uuid:xxx::upnp:rootdevice */
    val usn: String? get() = headers["usn"]

    /** 搜索目标 / 通知类型：ssdp:all | upnp:rootdevice | urn:schemas-upnp-org:... */
    val nt: String? get() = headers["nt"] ?: headers["st"]

    companion object {
        private val LINE_SPLIT = Regex("\\r?\\n")

        /** 从一行字节文本中解析出结构化消息 */
        fun parse(raw: String, sourceHost: String): SsdpMessage {
            val lines = raw.split(LINE_SPLIT)
            val statusLine = lines.firstOrNull() ?: ""

            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            val type = when {
                statusLine.startsWith("HTTP/1.1 200") -> SsdpMessageType.SEARCH_RESPONSE
                statusLine.startsWith("NOTIFY") -> when (headers["nts"]?.lowercase()) {
                    "ssdp:alive" -> SsdpMessageType.NOTIFY_ALIVE
                    "ssdp:byebye" -> SsdpMessageType.NOTIFY_BYEBYE
                    else -> SsdpMessageType.OTHER
                }
                // 我们自己的/别人的 M-SEARCH 也发到组播组，会收到，直接忽略
                statusLine.startsWith("M-SEARCH") -> SsdpMessageType.OTHER
                else -> SsdpMessageType.OTHER
            }
            return SsdpMessage(type, sourceHost, headers, raw)
        }
    }
}
