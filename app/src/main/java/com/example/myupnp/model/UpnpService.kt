package com.example.myupnp.model

/**
 * UPnP 服务（service）
 * ------------------------------------------------------------------
 * 一个 UPnP 设备由若干"服务"组成。每个服务描述三件事：
 *  1. 它是什么（serviceType）            e.g. urn:schemas-upnp-org:service:AVTransport:1
 *  2. 能做什么动作（通过 SCPD 文档描述）  -> scpdUrl 指向 SCPD XML
 *  3. 怎么调用（控制端点）                -> controlUrl 收 SOAP 请求
 *
 * 注意：description.xml 里这三个 URL 可能是相对路径（如 /upnp/control/AVTransport），
 * DeviceDescriptionLoader 加载后已统一解析成绝对地址。
 */
data class UpnpService(
    val serviceType: String = "",
    val serviceId: String = "",
    val scpdUrl: String = "",      // SCPD 描述文档地址（绝对）
    val controlUrl: String = "",   // 控制端点：SOAP POST 发到这里（绝对）
    val eventSubUrl: String = ""   // 事件订阅端点：GENA 用（绝对）
)
