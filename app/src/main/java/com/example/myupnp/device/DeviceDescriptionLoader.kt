package com.example.myupnp.device

import android.util.Xml
import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.model.UpnpService
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设备描述加载器
 * ------------------------------------------------------------------
 * SSDP 只告诉我们"设备在哪"（LOCATION），设备"长什么样"要再发一次
 * HTTP GET 拿 description.xml。典型内容（伪代码）：

   <root xmlns="urn:schemas-upnp-org:device-1-0">
     <specVersion><major>1</major><minor>0</minor></specVersion>
     <device>
       <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
       <friendlyName>My TV</friendlyName>          <- 界面要显示的名字
       <manufacturer>Xiaomi</manufacturer>
       <modelName>Mi TV</modelName>
       <UDN>uuid:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</UDN>
       <serviceList>
         <service>
           <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
           <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
           <controlURL>/upnp/control/AVTransport</controlURL>
           <eventSubURL>/upnp/event/AVTransport</eventSubURL>
           <SCPDURL>/upnp/AVTransport-scpd.xml</SCPDURL>
         </service>
       </serviceList>
     </device>
   </root>

 * 该加载器必须放在后台线程执行（HTTP 是阻塞 IO）。
 */
object DeviceDescriptionLoader {

    /**
     * 拉取并解析设备描述。
     * @param location SSDP 报文中 LOCATION 字段，形如 http://192.168.1.10:8200/rootDesc.xml
     * @return 解析出的设备；失败返回 null
     */
    fun load(location: String): UpnpDevice? {
        val connection: HttpURLConnection
        try {
            val url = URL(location)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                // 很多 UPnP 设备对没有 User-Agent 的请求很挑剔
                setRequestProperty("User-Agent", "MyUPNP/1.0 (Android)")
            }
        } catch (e: Exception) {
            return null
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.use { input ->
                val device = parseDeviceXml(input) ?: return null
                // 把服务里的相对 URL（很多设备写 /upnp/control/xxx）解析成
                // 基于 LOCATION 的绝对地址，供后续 SOAP/GENA 直接使用
                device.copy(services = device.services.map { svc ->
                    svc.copy(
                        scpdUrl = resolve(location, svc.scpdUrl),
                        controlUrl = resolve(location, svc.controlUrl),
                        eventSubUrl = resolve(location, svc.eventSubUrl)
                    )
                })
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 相对地址 -> 绝对地址：以描述文档的 LOCATION 为基准 */
    private fun resolve(baseUrl: String, url: String): String {
        if (url.isEmpty()) return ""
        return try {
            URL(URL(baseUrl), url).toString()
        } catch (_: Exception) {
            url // 解析失败就用原始值
        }
    }

    /**
     * 用 Android 自带的 XmlPullParser 逐节点读取。
     * 思路：找 <device> 节点，只解析它的直属子元素；
     * 遇到 <service> 递归解析成一个 UpnpService；
     * 遇到 <deviceList> 只数个数、跳过内容（嵌入设备后续课程再展开）。
     */
    private fun parseDeviceXml(input: InputStream): UpnpDevice? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var deviceType = ""
        var friendlyName = ""
        var manufacturer = ""
        var modelName = ""
        var modelNumber = ""
        var udn = ""
        val services = mutableListOf<UpnpService>()
        var embeddedCount = 0
        var foundDevice = false

        var event = parser.eventType
        // 我们用"当前处于哪个标签内"来避免误读嵌套结构
        var insideDevice = false
        var insideService = false
        var insideDeviceList = false
        var currentTag = ""
        var currentService = UpnpService()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    when {
                        tag == "device" && !insideDevice -> {
                            insideDevice = true
                            foundDevice = true
                        }
                        insideDevice && tag == "service" -> {
                            insideService = true
                            currentService = UpnpService()
                        }
                        insideDevice && tag == "deviceList" -> insideDeviceList = true
                        insideDeviceList && tag == "device" -> embeddedCount++
                        insideDevice && !insideService && !insideDeviceList -> {
                            currentTag = tag.lowercase()
                        }
                        insideService -> {
                            currentTag = tag.lowercase()
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty()) { /* 跳过纯空白 */ }
                    else when (currentTag) {
                        "devicetype" -> deviceType = text
                        "friendlyname" -> friendlyName = text
                        "manufacturer" -> manufacturer = text
                        "modelname" -> modelName = text
                        "modelnumber" -> modelNumber = text
                        "udn" -> udn = text
                        "servicetype" -> currentService =
                            currentService.copy(serviceType = text)
                        "serviceid" -> currentService =
                            currentService.copy(serviceId = text)
                        "scpdurl" -> currentService =
                            currentService.copy(scpdUrl = text)
                        "controlurl" -> currentService =
                            currentService.copy(controlUrl = text)
                        "eventsuburl" -> currentService =
                            currentService.copy(eventSubUrl = text)
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "service" -> {
                            if (insideService) {
                                services += currentService
                                insideService = false
                                currentTag = ""
                            }
                        }
                        "deviceList" -> insideDeviceList = false
                        "device" -> {
                            if (insideDevice && !insideService && !insideDeviceList) {
                                // 根 device 结束
                                if (foundDevice) {
                                    return UpnpDevice(
                                        deviceType = deviceType,
                                        friendlyName = friendlyName,
                                        manufacturer = manufacturer,
                                        modelName = modelName,
                                        modelNumber = modelNumber,
                                        udn = udn,
                                        services = services.toList(),
                                        embeddedDeviceCount = embeddedCount
                                    )
                                }
                                insideDevice = false
                            }
                        }
                        else -> currentTag = ""
                    }
                }
            }
            event = parser.next()
        }
        return null
    }
}
