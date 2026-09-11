package com.example.myupnp

import android.content.Context
import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.model.UpnpService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备列表快照缓存（体验修复：切后台/进程被杀后回来，列表不再空白）
 * ------------------------------------------------------------------
 * 把已知设备（含服务地址）序列化到 SharedPreferences：
 *   - 回到前台时先恢复旧列表，"看起来数据还在"
 *   - 同时自动续扫，SSDP 应答会把它们刷新成最新状态
 *   - 连服务地址都缓存，所以恢复后能立刻点开曲库/推送（不必等描述重新拉取）
 */
class DeviceCache(context: Context) {

    private val prefs = context.getSharedPreferences("device_cache", Context.MODE_PRIVATE)

    /**
     * 保存快照。
     * @param net 当前网络键 —— 设备属于某个局域网，换网络后旧快照不能再拿来用
     */
    fun save(entries: List<Entry>, net: String?) {
        val arr = JSONArray()
        for (e in entries) {
            val device = e.device ?: continue // 描述没拉到的先不缓存
            arr.put(
                JSONObject().apply {
                    put("loc", e.location)
                    put("usn", e.usn ?: "")
                    put("ip", e.ip)
                    put("dev", deviceToJson(device))
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).putString(KEY_NET, net ?: "").apply()
    }

    /** 读取快照：只返回与 [net] 同一网络的快照，否则视为无效（不串网） */
    fun load(net: String?): List<Entry> {
        val storedNet = prefs.getString(KEY_NET, "") ?: ""
        if (storedNet != (net ?: "")) return emptyList()
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val out = ArrayList<Entry>()
        runCatching {
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val location = o.optString("loc")
                if (location.isBlank()) continue
                out.add(
                    Entry(
                        location = location,
                        usn = o.optString("usn").ifBlank { null },
                        ip = o.optString("ip"),
                        device = o.optJSONObject("dev")?.let { deviceFromJson(it) },
                        failed = false,
                        lastSeen = now
                    )
                )
            }
        }
        return out
    }

    fun clear() = prefs.edit().remove(KEY).remove(KEY_NET).apply()

    private fun deviceToJson(d: UpnpDevice): JSONObject = JSONObject().apply {
        put("type", d.deviceType)
        put("name", d.friendlyName)
        put("manu", d.manufacturer)
        put("model", d.modelName)
        put("modelNum", d.modelNumber)
        put("udn", d.udn)
        put("embedded", d.embeddedDeviceCount)
        val svcArr = JSONArray()
        for (s in d.services) {
            svcArr.put(
                JSONObject().apply {
                    put("st", s.serviceType)
                    put("sid", s.serviceId)
                    put("scpd", s.scpdUrl)
                    put("ctl", s.controlUrl)
                    put("evt", s.eventSubUrl)
                }
            )
        }
        put("services", svcArr)
    }

    private fun deviceFromJson(o: JSONObject): UpnpDevice {
        val services = ArrayList<UpnpService>()
        val svcArr = o.optJSONArray("services")
        if (svcArr != null) {
            for (i in 0 until svcArr.length()) {
                val s = svcArr.optJSONObject(i) ?: continue
                services.add(
                    UpnpService(
                        serviceType = s.optString("st"),
                        serviceId = s.optString("sid"),
                        scpdUrl = s.optString("scpd"),
                        controlUrl = s.optString("ctl"),
                        eventSubUrl = s.optString("evt")
                    )
                )
            }
        }
        return UpnpDevice(
            deviceType = o.optString("type"),
            friendlyName = o.optString("name"),
            manufacturer = o.optString("manu"),
            modelName = o.optString("model"),
            modelNumber = o.optString("modelNum"),
            udn = o.optString("udn"),
            services = services,
            embeddedDeviceCount = o.optInt("embedded")
        )
    }

    private companion object {
        const val KEY = "snapshot"
        const val KEY_NET = "net"
    }
}
