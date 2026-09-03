package com.example.myupnp.model

/**
 * 一台 UPnP 设备（来自 description.xml 的 <device> 节点）
 * ------------------------------------------------------------------
 * 设备描述文档里最关键的信息：
 *  - deviceType    设备类型      e.g. urn:schemas-upnp-org:device:MediaRenderer:1
 *  - friendlyName  人类可读名字  e.g. "客厅电视" —— 界面上主要显示它
 *  - UDN           全局唯一标识  uuid:xxxx-xxxx —— 区分"同一台设备"的依据
 *  - services      该设备提供的服务列表（AVTransport、RenderingControl…）
 */
data class UpnpDevice(
    val deviceType: String = "",
    val friendlyName: String = "",
    val manufacturer: String = "",
    val modelName: String = "",
    val modelNumber: String = "",
    val udn: String = "",
    val services: List<UpnpService> = emptyList(),
    // 本课只展示根设备；若 deviceList 中有子设备（嵌入设备），用这个字段提示
    val embeddedDeviceCount: Int = 0
)
