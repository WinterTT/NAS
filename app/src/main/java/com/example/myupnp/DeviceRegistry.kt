package com.example.myupnp

import android.os.Handler
import android.util.Log
import com.example.myupnp.device.DeviceDescriptionLoader
import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.ssdp.SsdpMessage
import com.example.myupnp.ssdp.SsdpMessageType
import java.net.URL
import java.util.concurrent.ExecutorService

/**
 * 一台已知设备的记录（发现层的数据模型）。key = LOCATION。
 * 同一台设备可能有多个 USN（rootdevice / 设备类型 / 每个服务），
 * 但它们共享同一个 LOCATION，因此按 LOCATION 去重，只抓一次描述。
 */
data class Entry(
    val location: String,
    var usn: String? = null,
    var ip: String = "",
    var device: UpnpDevice? = null, // null = 描述还没拉下来/拉取失败
    var failed: Boolean = false,
    var lastSeen: Long = System.currentTimeMillis()
)

/**
 * 设备注册表（重构后独立成类）
 * ------------------------------------------------------------------
 * 职责：维护"当前发现的 UPnP 设备列表"及围绕它的数据逻辑：
 *   - 设备出现/下线登记（按 LOCATION 去重）
 *   - 心跳保活时间戳 + 超时清理（A1）
 *   - 异步拉取设备描述（description.xml）
 *   - 分组判断（MediaServer / 其他）
 *
 * 不碰任何 Android View —— UI 通过 [Listener.onRegistryChanged] 刷新。
 * 线程约定：方法都在主线程调用；拉描述丢给 [fetchExecutor]，完成 post 回
 * [mainHandler] 再写数据。
 */
class DeviceRegistry(
    private val mainHandler: Handler,
    private val fetchExecutor: ExecutorService,
    private val listener: Listener
) {

    /** 注册表变化通知 */
    interface Listener {
        /** 设备列表有任何变化（新增/描述更新/移除/清空），UI 重绘 */
        fun onRegistryChanged()
    }

    private val entries = LinkedHashMap<String, Entry>()

    // ------------------------------------------------------------------
    // 只读查询（UI / 控制逻辑用）
    // ------------------------------------------------------------------

    val size: Int get() = entries.size

    fun all(): List<Entry> = entries.values.toList()

    operator fun get(location: String): Entry? = entries[location]

    fun contains(location: String): Boolean = entries.containsKey(location)

    // ------------------------------------------------------------------
    // 设备出现 / 下线（由 SSDP 消息驱动，主线程）
    // ------------------------------------------------------------------

    /** 处理一条 SSDP 消息：登记/刷新/移除。true = 列表变了 */
    fun onSsdpMessage(message: SsdpMessage): Boolean {
        val changed = when (message.type) {
            SsdpMessageType.SEARCH_RESPONSE,
            SsdpMessageType.NOTIFY_ALIVE -> onDeviceSeen(message)
            SsdpMessageType.NOTIFY_BYEBYE -> onDeviceGone(message)
            SsdpMessageType.OTHER -> false
        }
        if (changed) listener.onRegistryChanged()
        return changed
    }

    /** 设备出现：登记（新设备自动拉描述），老设备刷新在线时间 */
    private fun onDeviceSeen(message: SsdpMessage): Boolean {
        val location = message.location ?: return false
        val ip = runCatching { URL(location).host }.getOrDefault("")

        val isNew = !entries.containsKey(location)
        if (isNew) {
            val entry = Entry(location, ip = ip)
            entries[location] = entry
            fetchDescription(entry)
            Log.i(TAG, "[DEVICE+] $ip  location=$location")
        } else {
            Log.v(TAG, "[DEVICE~] 已见过的设备刷新生效: $location")
        }
        val entry = entries[location]!!
        entry.lastSeen = System.currentTimeMillis()
        if (entry.usn == null) entry.usn = message.usn
        return isNew
    }

    /** 设备下线：按 USN 前缀（uuid）匹配，移除该设备全部记录 */
    private fun onDeviceGone(message: SsdpMessage): Boolean {
        val usn = message.usn ?: return false
        val goneUuid = usn.substringBefore("::")
        var removed = false
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (location, entry) = it.next()
            if (entry.usn?.substringBefore("::") == goneUuid) {
                it.remove()
                removed = true
                Log.i(TAG, "[DEVICE-] 下线移除: $location (usn=$usn)")
            }
        }
        return removed
    }

    // ------------------------------------------------------------------
    // 心跳清理（A1）：移除 staleMs 内无任何消息的设备
    // ------------------------------------------------------------------

    /**
     * 移除 [staleMs] 内没有任何消息的设备（断电没发 byebye 的兜底）。
     * @return 被移除的 Entry 列表（上层可能需要顺带清理订阅/播放条）
     */
    fun pruneStale(staleMs: Long): List<Entry> {
        val now = System.currentTimeMillis()
        val stale = entries.values.filter { now - it.lastSeen > staleMs }
        if (stale.isEmpty()) return emptyList()
        for (entry in stale) {
            entries.remove(entry.location)
            Log.i(
                TAG,
                "[DEVICE-] 心跳超时移除(静默${(now - entry.lastSeen) / 1000}s): " +
                    "${entry.device?.friendlyName ?: entry.usn ?: entry.location} @ ${entry.location}"
            )
        }
        listener.onRegistryChanged()
        return stale
    }

    /** 清空全部设备（手动清空 / 网络断开重建） */
    fun clearAll() {
        if (entries.isEmpty()) return
        entries.clear()
        listener.onRegistryChanged()
    }

    /**
     * 恢复缓存快照（切后台/进程被杀后回来时，先用旧数据把列表撑起来）。
     * 已存在的 LOCATION 不覆盖；恢复后照常靠 SSDP 应答刷新成最新状态。
     */
    fun restore(cached: List<Entry>) {
        var changed = false
        for (e in cached) {
            if (entries.containsKey(e.location)) continue
            entries[e.location] = e
            changed = true
        }
        if (changed) {
            Log.i(TAG, "[DEVICE~] 从缓存恢复 ${cached.size} 台设备快照")
            listener.onRegistryChanged()
        }
    }

    // ------------------------------------------------------------------
    // 描述拉取（后台线程，完成回主线程）
    // ------------------------------------------------------------------

    private fun fetchDescription(entry: Entry) {
        val location = entry.location
        Log.d(TAG, "[DESC>] 开始拉取描述: $location")
        fetchExecutor.execute {
            val device = DeviceDescriptionLoader.load(location)
            mainHandler.post {
                val live = entries[location] ?: return@post // 拉取期间设备已下线
                if (device != null) {
                    live.device = device
                    live.failed = false
                    Log.i(
                        TAG,
                        "[DESC<] 成功: ${device.friendlyName} | type=${device.deviceType} | " +
                            "${device.services.size} 个服务 | $location"
                    )
                } else {
                    live.failed = true
                    Log.w(TAG, "[DESC!] 拉取失败: $location")
                }
                listener.onRegistryChanged()
            }
        }
    }

    // ------------------------------------------------------------------
    // 分类工具
    // ------------------------------------------------------------------

    companion object {
        /** 判断设备类型：MediaServer（媒体服务器）或其它 */
        fun isMediaServer(device: UpnpDevice): Boolean {
            return device.deviceType.contains("MediaServer", ignoreCase = true) ||
                device.services.any { it.serviceType.contains("ContentDirectory", ignoreCase = true) }
        }

        private const val TAG = "MyUPNP"
    }
}
