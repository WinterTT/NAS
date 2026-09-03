package com.example.myupnp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myupnp.device.DeviceDescriptionLoader
import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.ssdp.SsdpDiscovery
import com.example.myupnp.ssdp.SsdpMessageType
import java.net.URL
import java.util.concurrent.Executors

/**
 * 第 1 课：UPnP 控制点 —— 设备发现 + 设备描述
 * ------------------------------------------------------------------
 * 整条链路（也是 UPnP 设备发现的标准三步）：
 *   1) 发 M-SEARCH（组播）            -> SsdpDiscovery
 *   2) 收 HTTP/1.1 200 OK（含 LOCATION）-> SsdpMessage
 *   3) GET LOCATION 拿 description.xml -> DeviceDescriptionLoader
 * 之后把解析出的 friendlyName/modelName 显示在列表里。
 *
 * Android 特有：必须持有 WifiManager.MulticastLock，Wi-Fi 网卡才会把
 * 组播帧交给应用（默认硬件层就丢了）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceTitle: TextView
    private lateinit var listDevices: ListView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val discovery = SsdpDiscovery(object : SsdpDiscovery.Listener {
        override fun onSsdpMessage(message: com.example.myupnp.ssdp.SsdpMessage) {
            mainHandler.post { handleSsdpMessage(message) }
        }

        override fun onEngineError(error: Throwable) {
            mainHandler.post { appendLog("!! 引擎错误: ${error.message}") }
        }
    })

    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 设备注册表：key = LOCATION。
     * 同一台设备可能有多个 USN（rootdevice / 设备类型 / 每个服务），
     * 但它们共享同一个 LOCATION，因此按 LOCATION 去重，只抓一次描述。
     */
    private data class Entry(
        val location: String,
        var usn: String? = null,
        var ip: String = "",
        var device: UpnpDevice? = null, // null = 描述还没拉下来/拉取失败
        var failed: Boolean = false
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val displayRows = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private val fetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "description-fetch").apply { isDaemon = true }
    }

    // ---- Android 13+ 需要运行时申请 NEARBY_WIFI_DEVICES ----
    private val nearbyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startScanningInternal()
            } else {
                Toast.makeText(this, R.string.nearby_wifi_rationale, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceTitle = findViewById(R.id.tvDeviceTitle)
        listDevices = findViewById(R.id.listDevices)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayRows)
        listDevices.adapter = adapter

        btnStart.setOnClickListener {
            if (discovery.isRunning()) return@setOnClickListener
            // Android 13+：局域网设备发现需要运行时权限
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                startScanningInternal()
            }
        }
        btnStop.setOnClickListener { stopScanning() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            tvLog.text = ""
            entries.clear()
            refreshDeviceList()
        }

        appendLog("=== MyUPNP 控制点启动 ===")
        appendLog("提示：确保手机和被测设备在同一 Wi-Fi 网段")
    }

    // ------------------------------------------------------------------
    // 扫描控制
    // ------------------------------------------------------------------
    private fun startScanningInternal() {
        appendLog(">> 请求 MulticastLock（否则收不到组播帧）")
        multicastLock = try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("myupnp-scan").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            appendLog("!! 获取 MulticastLock 失败: ${e.message}")
            null
        }

        appendLog(">> 开始监听组播组 ${SsdpDiscovery.GROUP_ADDRESS}:${SsdpDiscovery.PORT}")
        discovery.start()
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.setText(R.string.status_scanning)
    }

    private fun stopScanning() {
        discovery.stop()
        multicastLock?.let {
            runCatching { it.release() }
        }
        multicastLock = null
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.setText(R.string.status_idle)
        appendLog(">> 已停止扫描")
    }

    override fun onDestroy() {
        discovery.stop()
        multicastLock?.let {
            runCatching { it.release() }
        }
        multicastLock = null
        fetchExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 处理收到的 SSDP 消息（主线程）
    // ------------------------------------------------------------------
    private fun handleSsdpMessage(message: com.example.myupnp.ssdp.SsdpMessage) {
        appendLog(formatSsdpMessage(message))

        when (message.type) {
            SsdpMessageType.SEARCH_RESPONSE,
            SsdpMessageType.NOTIFY_ALIVE -> onDeviceSeen(message)

            SsdpMessageType.NOTIFY_BYEBYE -> onDeviceGone(message)
            SsdpMessageType.OTHER -> { /* 忽略 */ }
        }
    }

    /** 设备出现：登记并异步拉取描述 */
    private fun onDeviceSeen(message: com.example.myupnp.ssdp.SsdpMessage) {
        val location = message.location ?: return
        val ip = runCatching { URL(location).host }.getOrDefault("")

        val entry = entries[location] ?: Entry(location, ip = ip).also {
            entries[location] = it
            // 首次见到才拉描述；后续同 LOCATION 的消息只刷新在线状态
            fetchDescription(it)
        }
        if (entry.usn == null) entry.usn = message.usn
        refreshDeviceList()
    }

    /** 设备下线：按 USN 前缀匹配（同一 uuid 可能有多个 USN 后缀）移除 */
    private fun onDeviceGone(message: com.example.myupnp.ssdp.SsdpMessage) {
        val usn = message.usn ?: return
        val goneUuid = usn.substringBefore("::")
        val it = entries.entries.iterator()
        var removed = false
        while (it.hasNext()) {
            val (location, entry) = it.next()
            val entryUuid = entry.usn?.substringBefore("::")
            if (entryUuid == goneUuid) {
                it.remove()
                removed = true
                appendLog("-- 设备下线，移除: ${location}")
            }
        }
        if (removed) refreshDeviceList()
    }

    /** 后台线程拉取 description.xml，成功后回主线程更新 */
    private fun fetchDescription(entry: Entry) {
        val location = entry.location
        fetchExecutor.execute {
            val device = DeviceDescriptionLoader.load(location)
            mainHandler.post {
                val live = entries[location]
                if (live == null) return@post // 拉取期间设备已下线
                if (device != null) {
                    live.device = device
                    appendLog(
                        "  描述解析成功: ${device.friendlyName} | ${device.modelName} | " +
                            "${device.services.size} 个服务"
                    )
                } else {
                    live.failed = true
                    appendLog("!! 描述拉取失败: $location")
                }
                refreshDeviceList()
            }
        }
    }

    // ------------------------------------------------------------------
    // UI 刷新
    // ------------------------------------------------------------------
    private fun refreshDeviceList() {
        displayRows.clear()
        for (entry in entries.values) {
            val device = entry.device
            if (device != null) {
                displayRows +=
                    "${device.friendlyName.ifEmpty { "（未命名）" }}\n" +
                        "  型号: ${device.modelName.ifEmpty { "?" }} | " +
                        "制造商: ${device.manufacturer.ifEmpty { "?" }}\n" +
                        "  UDN: ${device.udn}\n" +
                        "  服务(${device.services.size}): " +
                        device.services.joinToString(", ") { it.serviceType.substringAfterLast(':') } +
                        "\n  @ ${entry.location}"
            } else {
                displayRows +=
                    "（等待描述…）\n  USN: ${entry.usn ?: "?"}\n  @ ${entry.location}" +
                    if (entry.failed) "\n  描述获取失败" else ""
            }
        }
        adapter.notifyDataSetChanged()
        tvDeviceTitle.text = getString(R.string.device_title) + "  (${entries.size})"
    }

    // ------------------------------------------------------------------
    // 日志（滚动到最新；限制长度防止长时间运行内存膨胀）
    // ------------------------------------------------------------------
    private fun appendLog(line: String) {
        tvLog.append(line + "\n")
        val text = tvLog.text
        if (text.length > MAX_LOG_CHARS) {
            tvLog.text = text.substring(text.length - MAX_LOG_CHARS)
        }
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun formatSsdpMessage(m: com.example.myupnp.ssdp.SsdpMessage): String {
        val typeName = when (m.type) {
            SsdpMessageType.SEARCH_RESPONSE -> "搜索应答"
            SsdpMessageType.NOTIFY_ALIVE -> "设备上线 NOTIFY"
            SsdpMessageType.NOTIFY_BYEBYE -> "设备下线 NOTIFY"
            SsdpMessageType.OTHER -> "其他"
        }
        val sb = StringBuilder()
        sb.append("[$typeName] from ${m.sourceHost}\n")
        for ((k, v) in m.headers) {
            sb.append("    $k: $v\n")
        }
        return sb.toString().trimEnd()
    }

    companion object {
        private const val MAX_LOG_CHARS = 200_000
    }
}
