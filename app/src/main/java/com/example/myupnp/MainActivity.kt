package com.example.myupnp

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import com.example.myupnp.device.ScpdLoader
import com.example.myupnp.model.UpnpAction
import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.model.UpnpService
import com.example.myupnp.soap.SoapCaller
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

        override fun onSsdpInfo(info: String) {
            mainHandler.post { appendLog("  · $info") }
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

    /** 列表刷新合并：消息风暴时每帧最多真正刷新一次 */
    private var refreshQueued = false

    /** 日志节流：同一类报文(类型+USN)在 LOG_COOLDOWN_MS 内只打印一次，防止刷屏 */
    private val logCooldown = HashMap<String, Long>()

    /** 日志缓冲：先攒在内存里，定时批量刷一次 TextView，避免每条都触发整段文本重排（会卡死主线程） */
    private val logBuffer = StringBuilder()

    /** 是否已排队一次日志 flush */
    private var logFlushPending = false

    private val fetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "description-fetch").apply { isDaemon = true }
    }

    /** SCPD/SOAP 控制请求的线程池（与描述抓取分开，避免互相排队拖慢） */
    private val controlExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soap-control").apply { isDaemon = true }
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

        // ---- 第 2 课：点设备 -> 选择要操作的服务（AVTransport/RenderingControl…） ----
        listDevices.setOnItemClickListener { _, _, position, _ ->
            val entry = entries.values.elementAtOrNull(position) ?: return@setOnItemClickListener
            val device = entry.device
            if (device == null) {
                Toast.makeText(this, "设备描述还没加载成功，无法操作", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            showServicePicker(entry, device)
        }

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
            logBuffer.setLength(0)
            logFlushPending = false
            tvLog.text = ""
            entries.clear()
            requestRefresh()
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
        controlExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 处理收到的 SSDP 消息（主线程）
    // ------------------------------------------------------------------
    private fun handleSsdpMessage(message: com.example.myupnp.ssdp.SsdpMessage) {
        // 日志节流：同样的(类型+USN)消息 10 秒内只打印一次
        val logKey = "${message.type}|${message.usn ?: message.location ?: message.sourceHost}"
        val nowMs = System.currentTimeMillis()
        val lastShown = logCooldown[logKey] ?: 0L
        if (nowMs - lastShown >= LOG_COOLDOWN_MS) {
            logCooldown[logKey] = nowMs
            if (logCooldown.size > 512) {
                val it = logCooldown.entries.iterator()
                while (logCooldown.size > 256 && it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            appendLog(formatSsdpMessage(message))
        }

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
        requestRefresh()
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
        if (removed) requestRefresh()
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
                requestRefresh()
            }
        }
    }

    /** 合并列表刷新：同帧内多次请求只刷一次（设备多时消息很密集） */
    private fun requestRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        mainHandler.post {
            refreshQueued = false
            refreshDeviceList()
        }
    }

    // ------------------------------------------------------------------
    // 第 2 课：SOAP 控制流程（全部在主线程弹 UI，网络请求丢给 controlExecutor）
    // ------------------------------------------------------------------

    /** 1) 选择设备里的一个服务 */
    private fun showServicePicker(entry: Entry, device: UpnpDevice) {
        if (device.services.isEmpty()) {
            Toast.makeText(this, "该设备没有可控制的服务", Toast.LENGTH_SHORT).show()
            return
        }
        val names = device.services.map { shortServiceName(it) }
        AlertDialog.Builder(this)
            .setTitle("${device.friendlyName} — 选择服务")
            .setItems(names.toTypedArray()) { _, which ->
                showActionPicker(entry, device.services[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 2) 拉取该服务的 SCPD，列出所有可用动作 */
    private fun showActionPicker(entry: Entry, service: UpnpService) {
        if (service.scpdUrl.isBlank()) {
            Toast.makeText(this, "该服务没有 SCPDURL", Toast.LENGTH_SHORT).show()
            return
        }
        appendLog(">> 拉取 SCPD(${shortServiceName(service)}): ${service.scpdUrl}")
        controlExecutor.execute {
            val actions = ScpdLoader.load(service.scpdUrl)
            mainHandler.post {
                if (actions.isEmpty()) {
                    appendLog("!! SCPD 解析失败或没有动作: ${service.scpdUrl}")
                    Toast.makeText(this, "SCPD 为空或解析失败", Toast.LENGTH_SHORT).show()
                    return@post
                }
                appendLog("  共 ${actions.size} 个动作:")
                actions.forEach { appendLog("    · ${it.signature()}") }
                AlertDialog.Builder(this)
                    .setTitle("${shortServiceName(service)} — 选择动作")
                    .setItems(actions.map { it.signature() }.toTypedArray()) { _, which ->
                        showArgumentInput(entry, service, actions[which])
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    /** 3) 给动作填 in 参数（常见参数预填默认值），点"调用"发 SOAP */
    private fun showArgumentInput(
        entry: Entry,
        service: UpnpService,
        action: UpnpAction
    ) {
        if (action.inArguments.isEmpty()) {
            doSoapCall(entry, service, action, emptyMap())
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }
        val edits = LinkedHashMap<String, EditText>()
        for (arg in action.inArguments) {
            val label = TextView(this).apply {
                text = "${arg.name} (in)"
                textSize = 14f
            }
            container.addView(label)
            val input = EditText(this).apply {
                hint = "值 (${arg.relatedStateVariable})"
                setText(DEFAULT_ARGS[arg.name] ?: "")
            }
            container.addView(input)
            edits[arg.name] = input
        }
        AlertDialog.Builder(this)
            .setTitle("调用 ${action.name} @ ${shortServiceName(service)}")
            .setView(container)
            .setPositiveButton("调用") { _, _ ->
                val args = edits.mapValues { it.value.text.toString().trim() }
                doSoapCall(entry, service, action, args)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 4) 真正发 SOAP 请求，结果打进协议日志 */
    private fun doSoapCall(
        entry: Entry,
        service: UpnpService,
        action: UpnpAction,
        args: Map<String, String>
    ) {
        if (service.controlUrl.isBlank()) {
            Toast.makeText(this, "该服务没有 controlURL", Toast.LENGTH_SHORT).show()
            return
        }
        appendLog(
            ">> SOAP 调用 ${action.name} @ ${service.controlUrl}  参数=$args " +
                "SOAPACTION=\"${service.serviceType}#${action.name}\""
        )
        controlExecutor.execute {
            val result = SoapCaller.call(service.controlUrl, service.serviceType, action.name, args)
            mainHandler.post {
                appendLog("<< 响应 http=${result.httpCode} success=${result.success}")
                if (result.success) {
                    appendLog("   响应体: ${result.body.take(600)}")
                    Toast.makeText(
                        this,
                        "${action.name} 调用成功（设备已受理）",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    appendLog("   调用失败: ${result.summary()}")
                    appendLog("   错误体片段: ${result.body.take(600)}")
                    Toast.makeText(
                        this,
                        "${action.name} 失败: ${result.upnpErrorDesc.take(60)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** 服务显示名：serviceId 最后一段（如 AVTransport） */
    private fun shortServiceName(s: UpnpService): String {
        val fromId = s.serviceId.substringAfterLast(':')
        if (fromId.isNotBlank()) return fromId
        val fromType = s.serviceType.substringAfter(":service:").substringBefore(":")
        return fromType.ifBlank { s.serviceType }
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
    // 日志（缓冲 + 定时批量刷新到 TextView）
    // 千万不要逐条 tvLog.append()：TextView 每 append/setText 一次都要对整个
    // 文本重新布局，日志一长（哪怕几千行）主线程就会被拖死导致"无响应"。
    // ------------------------------------------------------------------
    private fun appendLog(line: String) {
        logBuffer.append(line).append('\n')
        // 只保留尾部 MAX_LOG_CHARS 字符，防止日志无限增长
        if (logBuffer.length > MAX_LOG_CHARS * 2) {
            logBuffer.delete(0, logBuffer.length - MAX_LOG_CHARS)
        }
        if (!logFlushPending) {
            logFlushPending = true
            mainHandler.postDelayed({ flushLog() }, LOG_FLUSH_MS)
        }
    }

    /** 把缓冲里的日志一次性刷到界面（节流后每秒最多 LOG_FLUSH_MS 一次） */
    private fun flushLog() {
        logFlushPending = false
        if (logBuffer.isEmpty()) return
        tvLog.text = logBuffer.toString()
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
        /** 日志最多保留的尾部字符数（内存缓冲按 2 倍裁剪，界面最多显示这么多） */
        private const val MAX_LOG_CHARS = 60_000

        /** 相同报文(类型+USN)的最短打印间隔，防止设备多的局域网刷屏 */
        private const val LOG_COOLDOWN_MS = 10_000L

        /** 日志批量刷屏周期：攒够这段时间的日志再一次写到 TextView */
        private const val LOG_FLUSH_MS = 350L

        /** 填参数时预填的常见默认值（DLNA 媒体设备几乎都长这样） */
        private val DEFAULT_ARGS = mapOf(
            "InstanceID" to "0",   // AVTransport/RenderingControl 的实例号
            "Speed" to "1",        // Play 的播放速度
            "Channel" to "Master"  // RenderingControl 的音量通道
        )
    }
}
