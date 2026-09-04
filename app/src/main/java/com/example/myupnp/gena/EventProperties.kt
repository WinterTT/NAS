package com.example.myupnp.gena

/**
 * GENA 推送正文解析（纯 JVM，便于单测）
 * ------------------------------------------------------------------
 * 设备推过来的 NOTIFY 正文形如：

   <e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
     <e:property>
       <Volume>45</Volume>
       <Mute>0</Mute>
     </e:property>
   </e:propertyset>

 * 即：外层固定是 propertyset/property，里面每个子标签 = 一个状态变量，
 * 标签名 = 状态变量名，文本 = 新值。解析目标：把 (变量名, 新值) 全部抠出来。
 *
 * 注意：部分设备（尤其 DLNA）喜欢把变化打包成一个 LastChange 变量，
 * 里面是一整段转义过的 XML —— 那种情况我们至少能显示 LastChange 的原文，
 * 真正的展开解析留作课后思考（见 docs/03）。
 */
object EventProperties {

    /** 把 propertyset 正文解析成 (状态变量名, 新值) 列表 */
    fun parse(body: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()

        // 1) 找出所有 <e:property> ... </e:property> 块（前缀可能是 e: 或空）
        val propertyBlock = Regex(
            "<(?:\\w+:)?property\\b[^>]*>(.*?)</(?:\\w+:)?property>",
            RegexOption.DOT_MATCHES_ALL
        )
        for (block in propertyBlock.findAll(body)) {
            val inner = block.groupValues[1]

            // 2) 块内每个顶层子标签 = 一个状态变量
            //    只抓"无子标签、直接含文本"的叶子，避免把 LastChange 里的内层 XML 拆碎
            val tag = Regex("<([\\w.]+)(?:\\s[^>]*)?>([^<]*)</\\1>")
            for (m in tag.findAll(inner)) {
                result.add(m.groupValues[1] to unescape(m.groupValues[2].trim()))
            }

            // 3) 若块内一个简单叶子都没有（例如只有 <LastChange>&lt;xml&gt;...</LastChange>），
            //    就把块内容整体作为一条返回，至少让调用方能展示
            if (result.isEmpty() && inner.isNotBlank()) {
                result.add("(raw)" to unescape(inner).trim())
            }
        }
        return result
    }

    /** 反转 XML 实体转义，让值可读 */
    fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
