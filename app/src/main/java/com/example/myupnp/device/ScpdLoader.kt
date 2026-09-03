package com.example.myupnp.device

import android.util.Xml
import com.example.myupnp.model.UpnpAction
import com.example.myupnp.model.UpnpArgument
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * SCPD 加载器：解析一个服务"支持哪些动作"
 * ------------------------------------------------------------------
 * 设备描述(description.xml)里的每个 <service> 都有一个 SCPDURL，
 * 指向该服务的 SCPD 文档。典型结构：

   <scpd xmlns="urn:schemas-upnp-org:service-1-0">
     <specVersion><major>1</major><minor>0</minor></specVersion>
     <actionList>
       <action>
         <name>Play</name>                            <- 动作名
         <argumentList>
           <argument>
             <name>InstanceID</name>                  <- 参数名
             <direction>in</direction>                <- in=要传进去 / out=设备返回
             <relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable>
           </argument>
         </argumentList>
       </action>
       ... 更多动作 ...
     </actionList>
     <serviceStateTable>...</serviceStateTable>
   </scpd>

 * 我们只需要 actionList 里的 动作名 + 参数(name/direction)。
 */
object ScpdLoader {

    /**
     * 拉取并解析 SCPD。
     * @param scpdUrl 通常来自描述里的 <SCPDURL>，必须是绝对地址（相对地址在
     *                DeviceDescriptionLoader 里已被解析过）
     * @return 动作列表；失败返回空列表
     */
    fun load(scpdUrl: String): List<UpnpAction> {
        val connection: HttpURLConnection = try {
            val url = URL(scpdUrl)
            (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", "MyUPNP/1.0 (Android)")
            }
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) emptyList()
            else connection.inputStream.use { input -> parseActionList(input) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseActionList(input: InputStream): List<UpnpAction> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val actions = mutableListOf<UpnpAction>()
        var actionName = ""
        var currentArguments = mutableListOf<UpnpArgument>()

        var insideAction = false
        var insideArgument = false
        var currentTag = ""
        var currentArg = UpnpArgument()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    when {
                        tag == "action" && !insideAction -> {
                            insideAction = true
                            actionName = ""
                            currentArguments = mutableListOf()
                        }
                        insideAction && tag == "argument" && !insideArgument -> {
                            insideArgument = true
                            currentArg = UpnpArgument()
                        }
                        insideArgument -> currentTag = tag.lowercase()
                        insideAction && !insideArgument -> currentTag = tag.lowercase()
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty()) { /* 跳过空白 */ }
                    else when (currentTag) {
                        "name" -> if (insideArgument) {
                            currentArg = currentArg.copy(name = text)
                        } else {
                            actionName = text
                        }
                        "direction" -> currentArg = currentArg.copy(direction = text)
                        "relatedstatevariable" -> currentArg =
                            currentArg.copy(relatedStateVariable = text)
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "argument" -> if (insideArgument) {
                        currentArguments += currentArg
                        insideArgument = false
                        currentTag = ""
                    }
                    "action" -> if (insideAction) {
                        actions += UpnpAction(name = actionName, arguments = currentArguments.toList())
                        insideAction = false
                        currentTag = ""
                    }
                    else -> currentTag = "" // 离开任何叶子标签都清空，防止状态残留
                }
            }
            event = parser.next()
        }
        return actions
    }
}
