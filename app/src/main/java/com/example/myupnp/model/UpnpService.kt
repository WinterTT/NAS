package com.example.myupnp.model

/**
 * UPnP 服务（service）
 * ------------------------------------------------------------------
 * 一个 UPnP 设备由若干"服务"组成。每个服务描述三件事：
 *  1. 它是什么（serviceType）            e.g. urn:schemas-upnp-org:service:AVTransport:1
 *  2. 能做什么动作（通过 SCPD 文档描述）  -> scpdUrl 指向 SCPD XML
 *  3. 怎么调用（控制端点）                -> controlUrl 收 SOAP 请求
 *
 * 注意：这三个 URL 可能是相对路径，需要和设备的描述 URL 拼出绝对地址。
 */
data class UpnpService(
    val serviceType: String = "",
    val serviceId: String = "",
    val scpdUrl: String = "",      // SCPD 描述文档地址（本课暂不深入）
    val controlUrl: String = "",   // 控制端点（第 3 课 SOAP 调用会用到）
    val eventSubUrl: String = ""   // 事件订阅端点（第 4 课 GENA 会用到）
)
