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
import android.view.ViewGroup
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
import com.example.myupnp.dlna.DlnaPlayer
import com.example.myupnp.dlna.LastChangeParser
import com.example.myupnp.gena.EventProperties
import com.example.myupnp.gena.GenaClient
import com.example.myupnp.gena.LocalEventServer
import com.example.myupnp.gena.LocalIp
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
    private val deviceItems = ArrayList<DeviceListItem>()
    private lateinit var adapter: DeviceListAdapter

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

    // ------------------------------------------------------------------
    // 第 3 课：GENA 事件订阅状态
    // ------------------------------------------------------------------

    /** App 内嵌的回调服务器：收设备 NOTIFY 推送 */
    private val eventServer = LocalEventServer(object : LocalEventServer.Listener {
        override fun onEvent(remote: String, sid: String?, nts: String?, body: String) {
            mainHandler.post { handleIncomingEvent(remote, sid, nts, body) }
        }

        override fun onInfo(info: String) {
            mainHandler.post { appendLog("  · $info") }
        }
    })

    /** 一次订阅的记录：key = eventSubUrl */
    private data class Subscription(
        val eventSubUrl: String,
        val serviceName: String,
        var sid: String,
        var timeoutSec: Int
    )

    private val subscriptions = HashMap<String, Subscription>()

    /** 续订定时器：到期前自动再 SUBSCRIBE 一次（key = eventSubUrl） */
    private val renewRunnables = HashMap<String, Runnable>()

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

        adapter = DeviceListAdapter(this, deviceItems)
        listDevices.adapter = adapter

        // ---- 第 2~4 课：点设备 -> 选择场景（播放器/服务） ----
        listDevices.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as? DeviceListItem.DeviceItem
                ?: return@setOnItemClickListener // 分组标题不可点
            val entry = entries[item.entryKey] ?: return@setOnItemClickListener
            val device = entry.device
            if (device == null) {
                Toast.makeText(this, "设备描述还没加载成功，无法操作", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            showDeviceActions(entry, device)
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

        // 第 3 课：回调服务器要在 SUBSCRIBE 前就位，否则 CALLBACK 地址无效
        if (eventServer.port == 0) {
            eventServer.start()
            appendLog(">> 本机回调地址: ${eventCallbackUrl() ?: "（无法确定 IP）"}")
        }

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
        unsubscribeAll()       // 第 3 课：退出前先跟设备说再见
        eventServer.stop()     // 关掉回调服务器
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
        unsubscribeAll()
        eventServer.stop()
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

    /** 0) 点开一台设备：若是播放器给播放器场景，否则直接进服务操作 */
    private fun showDeviceActions(entry: Entry, device: UpnpDevice) {
        if (!DlnaPlayer.isRenderer(device)) {
            showServicePicker(entry, device)
            return
        }
        val renderer = DlnaPlayer.avTransportOf(device)!!
        val rc = DlnaPlayer.renderingControlOf(device)
        AlertDialog.Builder(this)
            .setTitle(device.friendlyName.ifEmpty { "播放器" })
            .setItems(
                arrayOf(
                    "▶ 播放器场景（推送 URL 播放）",
                    "服务控制 / 订阅（SOAP + GENA）"
                )
            ) { _, which ->
                when (which) {
                    0 -> showPlayerPanel(renderer, rc)
                    1 -> showServicePicker(entry, device)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 播放器面板：输入一个媒体 URL，就能让电视/音箱播起来。
     * 全流程复用前三课：SOAP 控制(SetAVTransportURI/Play/音量) + GENA 订阅(看进度)。
     */
    private fun showPlayerPanel(renderer: UpnpService, rc: UpnpService?) {
        // ---- 构建面板视图（课程演示用，简化为一个输入框 + 按钮行） ----
        val urlInput = EditText(this).apply {
            hint = "媒体 URL，如 http://192.168.1.50/video.mp4"
            textSize = 14f
        }
        val btnPlay = Button(this).apply { text = "推送播放" }
        val btnPause = Button(this).apply { text = "暂停" }
        val btnStop = Button(this).apply { text = "停止" }
        val btnVolDown = Button(this).apply { text = "音量−" }
        val btnVolUp = Button(this).apply { text = "音量＋" }
        val btnSubscribe = Button(this).apply { text = "订阅状态" }

        // 面板：输入框 + 2 行按钮
        fun row(vararg views: Button): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (v in views) {
                v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                r.addView(v)
            }
            return r
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlInput)
            addView(row(btnPlay, btnPause, btnStop))
            addView(row(btnVolDown, btnVolUp, btnSubscribe))
        }
        AlertDialog.Builder(this)
            .setTitle("DLNA 播放器场景")
            .setView(panel)
            .setNegativeButton("关闭", null)
            .show()

        // ---- 动作绑定：全部丢后台线程，结果打日志 ----
        btnPlay.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "先填媒体 URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            appendLog("▶ 播放器场景: 推送 $url")
            controlExecutor.execute {
                val results = DlnaPlayer.pushAndPlay(renderer, url)
                for ((name, r) in results) {
                    mainHandler.post {
                        appendLog(
                            if (r.success) "  ✔ $name 成功"
                            else "  ✘ $name 失败: ${r.summary()}"
                        )
                    }
                }
                // 推送成功后订阅进度事件，日志里就能看到播放状态变化
                if (results.lastOrNull()?.second?.success == true) {
                    mainHandler.post { if (!subscriptions.containsKey(renderer.eventSubUrl)) {
                        Toast.makeText(this, "已订阅播放状态，看下方日志", Toast.LENGTH_SHORT).show()
                        subscribeService(renderer)
                    } }
                }
            }
        }
        btnPause.setOnClickListener {
            controlExecutor.execute {
                val r = DlnaPlayer.transportAction(renderer, "Pause")
                mainHandler.post { logActionResult("Pause", r) }
            }
        }
        btnStop.setOnClickListener {
            controlExecutor.execute {
                val r = DlnaPlayer.transportAction(renderer, "Stop")
                mainHandler.post { logActionResult("Stop", r) }
            }
        }
        btnVolDown.setOnClickListener { if (rc != null) stepVolume(rc, -10) }
        btnVolUp.setOnClickListener { if (rc != null) stepVolume(rc, 10) }
        btnSubscribe.setOnClickListener { subscribeService(renderer) }
    }

    private fun stepVolume(rc: UpnpService, delta: Int) {
        appendLog("▶ 音量 ${if (delta > 0) "+" else ""}$delta")
        controlExecutor.execute {
            val before = DlnaPlayer.getVolume(rc)
            val r = DlnaPlayer.stepVolume(rc, delta)
            val after = if (r.success) DlnaPlayer.getVolume(rc) else -1
            mainHandler.post {
                if (r.success) {
                    appendLog("  音量 $before -> $after")
                } else {
                    logActionResult("SetVolume", r)
                }
            }
        }
    }

    private fun logActionResult(action: String, r: SoapCaller.SoapResult) {
        if (r.success) appendLog("  ✔ $action 成功")
        else appendLog("  ✘ $action 失败: ${r.summary()}")
    }

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
                val service = device.services[which]
                showServiceMenu(entry, service)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 1.5) 对一个服务：控制(SOAP) / 订阅事件(GENA) / 退订 */
    private fun showServiceMenu(entry: Entry, service: UpnpService) {
        val subscribed = subscriptions.containsKey(service.eventSubUrl)
        val menu = mutableListOf("① 动作控制 (SOAP)")
        if (!subscribed) {
            menu += "② 订阅事件推送 (GENA)"
        } else {
            menu += "② 续订事件 (已订阅)"
            menu += "③ 退订事件"
        }
        AlertDialog.Builder(this)
            .setTitle(shortServiceName(service))
            .setItems(menu.toTypedArray()) { _, which ->
                when (menu[which]) {
                    "① 动作控制 (SOAP)" -> showActionPicker(entry, service)
                    "② 订阅事件推送 (GENA)" -> subscribeService(service)
                    "② 续订事件 (已订阅)" -> renewSubscription(service.eventSubUrl)
                    "③ 退订事件" -> unsubscribeService(service)
                }
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
    // 第 3 课：GENA 事件订阅
    // ------------------------------------------------------------------

    /** 订阅一个服务的事件推送 */
    private fun subscribeService(service: UpnpService) {
        val eventUrl = service.eventSubUrl
        if (eventUrl.isBlank()) {
            Toast.makeText(this, "该服务没有 eventSubURL", Toast.LENGTH_SHORT).show()
            return
        }
        if (subscriptions.containsKey(eventUrl)) {
            Toast.makeText(this, "已在订阅中，正在续订", Toast.LENGTH_SHORT).show()
            renewSubscription(eventUrl)
            return
        }
        val callbackUrl = eventCallbackUrl()
        if (callbackUrl == null) {
            Toast.makeText(this, "无法确定本机 IP，订阅失败", Toast.LENGTH_LONG).show()
            return
        }
        appendLog(
            ">> SUBSCRIBE ${shortServiceName(service)} @ $eventUrl\n" +
                "   回调地址 CALLBACK: $callbackUrl（设备将往这里推事件）"
        )
        controlExecutor.execute {
            val result = GenaClient.subscribe(eventUrl, callbackUrl)
            mainHandler.post {
                if (result.ok) {
                    val sub = Subscription(
                        eventSubUrl = eventUrl,
                        serviceName = shortServiceName(service),
                        sid = result.sid,
                        timeoutSec = result.timeoutSec.let { if (it <= 0) 1800 else it }
                    )
                    subscriptions[eventUrl] = sub
                    appendLog("<< 订阅成功 SID=${result.sid} TIMEOUT=${sub.timeoutSec}s，已安排自动续订")
                    scheduleRenewal(eventUrl)
                } else {
                    appendLog("!! 订阅失败: ${result.message}")
                    Toast.makeText(this, "订阅失败: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 到期前自动续订（手动或定时触发） */
    private fun renewSubscription(eventUrl: String) {
        val sub = subscriptions[eventUrl] ?: return
        appendLog(">> RENEW ${sub.serviceName} SID=${sub.sid}")
        controlExecutor.execute {
            val result = GenaClient.renew(eventUrl, sub.sid, 1800)
            mainHandler.post {
                if (result.ok) {
                    sub.sid = result.sid.ifEmpty { sub.sid }
                    sub.timeoutSec = result.timeoutSec.let { if (it <= 0) 1800 else it }
                    appendLog("<< 续订成功，新 TIMEOUT=${sub.timeoutSec}s")
                    scheduleRenewal(eventUrl)
                } else {
                    appendLog("!! 续订失败: ${result.message}（订阅可能已失效）")
                    subscriptions.remove(eventUrl)
                    renewRunnables.remove(eventUrl)?.let { mainHandler.removeCallbacks(it) }
                }
            }
        }
    }

    /** 退订：停止接收该服务的事件 */
    private fun unsubscribeService(service: UpnpService) {
        val eventUrl = service.eventSubUrl
        val sub = subscriptions[eventUrl]
        if (sub == null) {
            Toast.makeText(this, "该服务尚未订阅", Toast.LENGTH_SHORT).show()
            return
        }
        appendLog(">> UNSUBSCRIBE ${sub.serviceName} SID=${sub.sid}")
        controlExecutor.execute {
            val result = GenaClient.unsubscribe(eventUrl, sub.sid)
            mainHandler.post {
                if (result.ok) {
                    appendLog("<< 退订成功")
                } else {
                    appendLog("!! 退订失败: ${result.message}")
                }
                subscriptions.remove(eventUrl)
                renewRunnables.remove(eventUrl)?.let { mainHandler.removeCallbacks(it) }
            }
        }
    }

    /** 按设备退出时的清理：退掉所有订阅 */
    private fun unsubscribeAll() {
        val snapshot = subscriptions.values.toList()
        for (sub in snapshot) {
            appendLog(">> 清理: UNSUBSCRIBE ${sub.serviceName}")
            controlExecutor.execute {
                GenaClient.unsubscribe(sub.eventSubUrl, sub.sid)
            }
        }
        subscriptions.clear()
        renewRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        renewRunnables.clear()
    }

    /** 在 timeout 的一半时间点自动续订一次 */
    private fun scheduleRenewal(eventUrl: String) {
        val sub = subscriptions[eventUrl] ?: return
        renewRunnables.remove(eventUrl)?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { renewSubscription(eventUrl) }
        renewRunnables[eventUrl] = runnable
        val delayMs = (sub.timeoutSec * 1_000L / 2).coerceAtLeast(5_000L)
        mainHandler.postDelayed(runnable, delayMs)
    }

    /** 构造本机回调地址 http://<本机IP>:<端口>/upnp/event/cb；服务器没起来返回 null */
    private fun eventCallbackUrl(): String? {
        val ip = LocalIp.ipv4() ?: return null
        if (eventServer.port == 0) return null
        return "http://$ip:${eventServer.port}/upnp/event/cb"
    }

    /** 收到设备推送（LocalEventServer 回调，已在主线程） */
    private fun handleIncomingEvent(remote: String, sid: String?, nts: String?, body: String) {
        appendLog("◀ 事件推送 from $remote NTS=${nts ?: "?"} SID=${sid ?: "?"}")
        val props = EventProperties.parse(body)
        if (props.isEmpty()) {
            appendLog("   (空属性)")
        }
        for ((name, value) in props) {
            // 第 4 课：LastChange 是转义嵌套 XML，展开成可读状态（TransportState 等）
            if (name == "LastChange") {
                appendLog("   LastChange 展开:")
                val lc = LastChangeParser.parse(value)
                if (lc.transportState != null) appendLog("     播放状态 TransportState = ${lc.transportState}")
                if (lc.currentTrackDuration != null) appendLog("     总时长 = ${lc.currentTrackDuration}")
                if (lc.relativeTimePosition != null) appendLog("     已播位置 = ${lc.relativeTimePosition}")
                if (lc.volume != null) appendLog("     音量 = ${lc.volume}")
                if (lc.mute != null) appendLog("     静音 = ${lc.mute}")
                if (lc.values.isNotEmpty()) {
                    val extra = lc.values.entries
                        .filter { it.key !in listOf("TransportState", "CurrentTrackDuration", "RelativeTimePosition", "Volume", "Mute") }
                        .joinToString(", ") { "${it.key}=${it.value}" }
                    if (extra.isNotBlank()) appendLog("     其他: $extra")
                }
            } else {
                appendLog("   $name = $value")
            }
        }
    }

    // ------------------------------------------------------------------
    // UI 刷新
    // ------------------------------------------------------------------

    /** 判断设备类型：MediaServer（媒体服务器）或其它 */
    private fun isMediaServer(device: UpnpDevice): Boolean {
        // 设备类型 URN 通常形如 urn:schemas-upnp-org:device:MediaServer:1；
        // 有些设备类型写得不标准，就用特征服务 ContentDirectory 兜底判断
        return device.deviceType.contains("MediaServer", ignoreCase = true) ||
            device.services.any { it.serviceType.contains("ContentDirectory", ignoreCase = true) }
    }

    /** 重新生成分组列表：MediaServer 一组，其它一组 */
    private fun refreshDeviceList() {
        deviceItems.clear()

        // 先按类型分桶，保持每桶内按发现顺序
        val mediaServer = ArrayList<Entry>()
        val others = ArrayList<Entry>()
        for (entry in entries.values) {
            val device = entry.device
            if (device != null && isMediaServer(device)) mediaServer.add(entry)
            else others.add(entry)
        }

        fun appendGroup(title: String, group: List<Entry>) {
            if (group.isEmpty()) return
            deviceItems += DeviceListItem.Header(title)
            for (entry in group) {
                deviceItems += DeviceListItem.DeviceItem(entry.location, formatDeviceText(entry))
            }
        }

        appendGroup("📦 MediaServer（${mediaServer.size}）", mediaServer)
        appendGroup("其他设备（${others.size}）", others)

        adapter.notifyDataSetChanged()
        tvDeviceTitle.text = getString(R.string.device_title) + "  (${entries.size})"
    }

    /** 一台设备的多行展示文本 */
    private fun formatDeviceText(entry: Entry): String {
        val device = entry.device
        return if (device != null) {
            "${device.friendlyName.ifEmpty { "（未命名）" }}\n" +
                "  型号: ${device.modelName.ifEmpty { "?" }} | " +
                "制造商: ${device.manufacturer.ifEmpty { "?" }}\n" +
                "  类型: ${device.deviceType.substringAfterLast(':').ifEmpty { device.deviceType }}\n" +
                "  服务(${device.services.size}): " +
                device.services.joinToString(", ") { it.serviceType.substringAfterLast(':') } +
                "\n  @ ${entry.location}"
        } else {
            "（等待描述…）\n  USN: ${entry.usn ?: "?"}\n  @ ${entry.location}" +
                if (entry.failed) "\n  描述获取失败" else ""
        }
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
