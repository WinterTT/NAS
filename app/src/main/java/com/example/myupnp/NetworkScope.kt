package com.example.myupnp

import com.example.myupnp.gena.LocalIp

/**
 * 网络作用域（第 9 课：数据按网络隔离）
 * ------------------------------------------------------------------
 * DLNA 设备与媒体 URL 都属于"某个局域网"（192.168.x.x）。换 Wi-Fi 后，
 * 队列里的歌、历史里的地址、上次播放的设备都可能不可达，所以这些数据
 * 要按网络分开存。
 *
 * 网络标识用**网段**（IPv4 前三段，如 "192.168.1"）：
 *   - 不需要读取 SSID，因此不需要额外定位权限
 *   - 家庭/公司/热点几乎必然不同网段，足够区分
 */
object NetworkScope {

    /** 由 IP 得到网络键，如 "net:192.168.1"；拿不到 IP 返回 null */
    fun keyOf(ip: String?): String? {
        val clean = ip?.trim().orEmpty()
        if (clean.isEmpty()) return null
        val prefix = clean.substringBeforeLast('.', clean)
        return if (prefix.isEmpty()) null else "net:$prefix"
    }

    /** 当前网络键（取不到本机 IP 时为 null = 未知网络） */
    fun current(): String? = keyOf(LocalIp.ipv4())

    /** 展示用：net:192.168.1 → 192.168.1.x */
    fun labelOf(key: String?): String =
        key?.removePrefix("net:")?.let { "$it.x" } ?: "未知网络"
}
