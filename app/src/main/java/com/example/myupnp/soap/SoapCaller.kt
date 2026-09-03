package com.example.myupnp.soap

import java.net.HttpURLConnection
import java.net.URL

/**
 * SOAP 控制调用器：真正让设备"干活"的代码
 * ------------------------------------------------------------------
 * 概念：SCPD 告诉我们服务有哪些动作(Play/Pause/SetVolume…)，
 * SOAP 则是"执行动作"的协议。执行 = 向 controlUrl 发一个 HTTP POST，
 * 请求体是 XML"信封"(Envelope)：

   POST /upnp/control/AVTransport HTTP/1.1
   HOST: 192.168.1.10:8200
   CONTENT-TYPE: text/xml; charset="utf-8"
   SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#Play"   <- 关键：动作名拼服务类型
   Content-Length: ...

   <?xml version="1.0" encoding="utf-8"?>
   <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
               s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
     <s:Body>
       <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">   <- 动作名做根标签
         <InstanceID>0</InstanceID>                                     <- in 参数
         <Speed>1</Speed>
       </u:Play>
     </s:Body>
   </s:Envelope>

 * 设备返回 200 + 响应信封（含 out 参数），或 500 + <s:Fault>（出错）。
 *
 * 本类只做"构造 + 发送 + 粗解析"，XML 细节对调用方隐藏。
 */
object SoapCaller {

    /** 一次 SOAP 调用的结果 */
    data class SoapResult(
        val httpCode: Int,
        val body: String,                 // 响应全文（调试学习用）
        val success: Boolean,
        val faultString: String = "",     // 出错时 <faultstring> 内容，如 "UPnPError"
        val upnpErrorCode: Int = 0,       // 设备自定义错误码，如 701(无该动作)
        val upnpErrorDesc: String = ""    // 设备返回的错误描述
    ) {
        val isFault: Boolean get() = !success
        fun summary(): String =
            if (success) "OK($httpCode)"
            else "Fault http=$httpCode code=$upnpErrorCode: $upnpErrorDesc (faultstring=$faultString)"
    }

    private const val SOAP_NS =
        "http://schemas.xmlsoap.org/soap/envelope/"

    /**
     * 调用一个 UPnP 服务动作。
     * @param serviceType 服务类型 URN，如 urn:schemas-upnp-org:service:AVTransport:1
     * @param actionName  动作名，如 Play
     * @param args        in 参数，如 mapOf("InstanceID" to "0", "Speed" to "1")
     */
    fun call(
        controlUrl: String,
        serviceType: String,
        actionName: String,
        args: Map<String, String>
    ): SoapResult {
        val conn: HttpURLConnection = try {
            val url = URL(controlUrl)
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                // SOAP 1.1 的关键首部：SOAPACTION = "服务类型#动作名"
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$serviceType#$actionName\"")
                setRequestProperty("User-Agent", "MyUPNP/1.0 (Android)")
                doOutput = true
            }
        } catch (e: Exception) {
            return SoapResult(0, "连接失败: ${e.message}", false)
        }

        return try {
            val body = buildEnvelope(serviceType, actionName, args)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            // 200 = 成功；500 = 出错(Fault)。错误信息在 errorStream 里
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code in 200..299) {
                SoapResult(code, respBody, success = true)
            } else {
                parseFault(code, respBody)
            }
        } catch (e: Exception) {
            SoapResult(0, "请求异常: ${e.message}", false)
        } finally {
            conn.disconnect()
        }
    }

    /** 构造 SOAP 信封（XML 请求体） */
    private fun buildEnvelope(
        serviceType: String,
        actionName: String,
        args: Map<String, String>
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"$SOAP_NS\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n")
        append("<s:Body>\n")
        append("<u:$actionName xmlns:u=\"$serviceType\">\n")
        for ((k, v) in args) {
            append("<$k>${xmlEscape(v)}</$k>\n")
        }
        append("</u:$actionName>\n")
        append("</s:Body>\n")
        append("</s:Envelope>\n")
    }

    /** 出错时解析 <s:Fault>，尽量挖出 UPnPError 错误码和描述 */
    private fun parseFault(httpCode: Int, body: String): SoapResult {
        var faultString = ""
        var errCode = 0
        var errDesc = ""
        // UPnP 错误码藏在 <errorCode> 里；描述在 <errorDescription>；
        // 有些设备只给 <faultstring>。三个都抓，抓不到就显示原始片段。
        Regex("<errorCode>\\s*(\\d+)\\s*</errorCode>").find(body)?.let {
            errCode = it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("<errorDescription>(.*?)</errorDescription>").find(body)?.let {
            errDesc = it.groupValues[1].trim()
        }
        Regex("<faultstring>(.*?)</faultstring>").find(body)?.let {
            faultString = it.groupValues[1].trim()
        }
        if (errDesc.isEmpty()) {
            // 兜底：从 body 里截一段可读文本
            errDesc = body.take(300)
        }
        return SoapResult(httpCode, body, success = false, faultString = faultString,
            upnpErrorCode = errCode, upnpErrorDesc = errDesc)
    }

    /** XML 特殊字符转义（参数值里可能有 & < > 等） */
    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
