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
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
import com.example.myupnp.dlna.ContentDirectoryClient
import com.example.myupnp.dlna.DlnaPlayer
import com.example.myupnp.dlna.LastChangeParser
import com.example.myupnp.gena.EventProperties
import com.example.myupnp.gena.GenaClient
import com.example.myupnp.gena.LocalEventServer
import com.example.myupnp.gena.LocalIp
import com.example.myupnp.model.MediaContainer
import com.example.myupnp.model.MediaItem
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
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val discovery = SsdpDiscovery(object : SsdpDiscovery.Listener {
        override fun onSsdpMessage(message: com.example.myupnp.ssdp.SsdpMessage) {
            mainHandler.post { handleSsdpMessage(message) }
        }

        override fun onEngineError(error: Throwable) {
            Log.w(TAG, "[SSDP!] 引擎错误: ${error.message}")
            mainHandler.post { appendLog("!! 引擎错误: ${error.message}") }
        }

        override fun onSsdpInfo(info: String) {
            Log.d(TAG, "[SSDP] $info")
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
            entries.clear()
            requestRefresh()
        }

        Log.i(TAG, "[UI] MyUPNP 启动完成，等待用户操作")
    }

    // ------------------------------------------------------------------
    // 扫描控制
    // ------------------------------------------------------------------
    private fun startScanningInternal() {
        Log.i(TAG, "[SCAN] 开始扫描，请求 MulticastLock")
        appendLog(">> 请求 MulticastLock（否则收不到组播帧）")
        multicastLock = try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("myupnp-scan").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[SCAN] MulticastLock 获取失败: ${e.message}")
            appendLog("!! 获取 MulticastLock 失败: ${e.message}")
            null
        }

        appendLog(">> 开始监听组播组 ${SsdpDiscovery.GROUP_ADDRESS}:${SsdpDiscovery.PORT}")
        discovery.start()

        // 第 3 课：回调服务器要在 SUBSCRIBE 前就位，否则 CALLBACK 地址无效
        if (eventServer.port == 0) {
            eventServer.start()
            val cb = eventCallbackUrl()
            Log.i(TAG, "[SCAN] 事件回调服务器端口=${eventServer.port} 本机回调=$cb")
            appendLog(">> 本机回调地址: ${cb ?: "（无法确定 IP）"}")
        }

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.setText(R.string.status_scanning)
    }

    private fun stopScanning() {
        Log.i(TAG, "[SCAN] 停止扫描")
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
        // logcat：不经 UI 节流，设备多时也逐条留痕，方便 adb logcat -s MyUPNP 排查
        Log.d(
            TAG,
            "[SSDP:${message.type}] from=${message.sourceHost} " +
                "usn=${message.usn ?: "-"} loc=${message.location ?: "-"} " +
                "nt=${message.nt ?: "-"}"
        )
        // UI 日志区已移除：报文细节全部走 logcat（见上方 Log.d），这里只管业务处理

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

        val isNew = !entries.containsKey(location)
        val entry = entries[location] ?: Entry(location, ip = ip).also {
            entries[location] = it
            // 首次见到才拉描述；后续同 LOCATION 的消息只刷新在线状态
            fetchDescription(it)
        }
        if (entry.usn == null) entry.usn = message.usn
        if (isNew) Log.i(TAG, "[DEVICE+] $ip  location=$location")
        else Log.v(TAG, "[DEVICE~] 已见过的设备刷新生效: $location")
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
                Log.i(TAG, "[DEVICE-] 下线移除: $location (usn=$usn)")
                appendLog("-- 设备下线，移除: ${location}")
            }
        }
        if (removed) requestRefresh()
    }

    /** 后台线程拉取 description.xml，成功后回主线程更新 */
    private fun fetchDescription(entry: Entry) {
        val location = entry.location
        Log.d(TAG, "[DESC>] 开始拉取描述: $location")
        fetchExecutor.execute {
            val device = DeviceDescriptionLoader.load(location)
            mainHandler.post {
                val live = entries[location]
                if (live == null) return@post // 拉取期间设备已下线
                if (device != null) {
                    live.device = device
                    Log.i(
                        TAG,
                        "[DESC<] 成功: ${device.friendlyName} | type=${device.deviceType} | " +
                            "${device.services.size} 个服务 | $location"
                    )
                    appendLog(
                        "  描述解析成功: ${device.friendlyName} | ${device.modelName} | " +
                            "${device.services.size} 个服务"
                    )
                } else {
                    live.failed = true
                    Log.w(TAG, "[DESC!] 拉取失败: $location")
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

    /** 0) 点开一台设备：播放器给播放场景；MediaServer 给曲库浏览；其它进服务操作 */
    private fun showDeviceActions(entry: Entry, device: UpnpDevice) {
        if (DlnaPlayer.isRenderer(device)) {
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
            return
        }
        if (isMediaServer(device)) {
            val cds = device.services.firstOrNull { it.serviceType.contains("ContentDirectory") }
            if (cds != null) {
                val menus = mutableListOf("📁 浏览媒体库（ContentDirectory）")
                val actions = mutableListOf<() -> Unit>()
                actions += { showMediaBrowser(cds) }
                // 如果同一台还挂别的服务，也给出口
                if (device.services.any { it.serviceType.contains("ContentDirectory").not() }) {
                    menus += "服务控制 / 订阅（SOAP + GENA）"
                    actions += { showServicePicker(entry, device) }
                }
                AlertDialog.Builder(this)
                    .setTitle(device.friendlyName.ifEmpty { "MediaServer" })
                    .setItems(menus.toTypedArray()) { _, which ->
                        actions[which]()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }
        showServicePicker(entry, device)
    }

    /**
     * 曲库浏览器：从根开始逐层 Browse，点歌曲推给播放器。
     * 用简单对话框做导航，breadcrumb 保留当前路径用于"返回上级"。
     */
    private fun showMediaBrowser(cds: UpnpService) {
        showMediaLevel(cds, ContentDirectoryClient.ROOT_OBJECT_ID, emptyList())
    }

    private fun showMediaLevel(
        cds: UpnpService,
        objectId: String,
        crumb: List<Pair<String, String>> // (id, 标题) 栈，最右为当前层
    ) {
        appendLog(">> Browse ${crumb.lastOrNull()?.second ?: "根目录"} ($objectId) @ ${cds.controlUrl}")
        Log.d(
            TAG,
            "[CDS>] Browse objectId=$objectId ctrl=${cds.controlUrl} serviceType=${cds.serviceType}"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(crumb.lastOrNull()?.second ?: "媒体库")
            .setMessage("正在加载…")
            .setNegativeButton("关闭", null)
            .show()

        controlExecutor.execute {
            val out = ContentDirectoryClient.browse(cds, objectId)
            mainHandler.post {
                if (dialog.isShowing) dialog.dismiss()
                if (!out.ok) {
                    Log.e(TAG, "[CDS!] Browse 失败 objectId=$objectId: ${out.error}")
                    if (out.rawSnippet.isNotBlank()) Log.e(TAG, "[CDS!] 响应片段: ${out.rawSnippet}")
                    appendLog("!! Browse 失败: ${out.error}")
                    if (out.rawSnippet.isNotBlank()) appendLog("   响应片段: ${out.rawSnippet}")
                    Toast.makeText(this, "Browse 失败: ${out.error}", Toast.LENGTH_LONG).show()
                    return@post
                }
                if (out.objects.isEmpty()) {
                    Log.w(TAG, "[CDS] Browse 成功但目录为空 objectId=$objectId")
                    appendLog("  该目录为空（没有子项）")
                    Toast.makeText(this, "目录是空的", Toast.LENGTH_SHORT).show()
                    return@post
                }
                Log.i(
                    TAG,
                    "[CDS<] Browse 返回 ${out.objects.size} 项: " +
                        out.objects.joinToString(", ") { "${it.title}[${if (it.isContainer) "dir" else "file"}]" }
                )

                // 组装菜单：前面加"返回上级"
                val labels = ArrayList<String>()
                val itemActions = ArrayList<() -> Unit>()
                if (crumb.isNotEmpty()) {
                    labels += "⬅ 返回上级"
                    itemActions += {
                        val parent = crumb.last()
                        showMediaLevel(cds, parent.first, crumb.dropLast(1))
                    }
                }
                for (obj in out.objects) {
                    labels += obj.displayText()
                    if (obj is MediaContainer) {
                        itemActions += { showMediaLevel(cds, obj.id, crumb + (obj.id to obj.title)) }
                    } else if (obj is MediaItem) {
                        itemActions += { playMediaItemFromServer(obj) }
                    }
                }

                AlertDialog.Builder(this)
                    .setTitle(crumb.lastOrNull()?.second ?: "媒体库")
                    .setItems(labels.toTypedArray()) { _, which ->
                        itemActions[which]()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    /** 曲库里点歌：先列出所有可播放设备让用户选（只有一台才直接播） */
    private fun playMediaItemFromServer(item: MediaItem) {
        if (item.resUrl.isBlank()) {
            Toast.makeText(this, "该条目没有可播放地址(res)", Toast.LENGTH_SHORT).show()
            return
        }
        // 收集所有 MediaRenderer（有 AVTransport 服务），按发现顺序
        val renderers = entries.values
            .mapNotNull { e -> e.device?.let { d -> (DlnaPlayer.avTransportOf(d))?.let { avt -> e to avt } } }
            .filter { it.first.device != null }

        if (renderers.isEmpty()) {
            Toast.makeText(this, "没发现可播放的 MediaRenderer（音响/电视）", Toast.LENGTH_LONG).show()
            return
        }
        if (renderers.size == 1) {
            pushToRenderer(renderers[0].second, renderers[0].first.device!!.friendlyName, item)
            return
        }
        // 多台播放设备 -> 弹框让用户选推给谁
        val names = renderers.map { (e, _) ->
            val d = e.device!!
            "📺 ${d.friendlyName.ifEmpty { "未命名" }}  ${d.modelName}"
        }
        AlertDialog.Builder(this)
            .setTitle("推送给哪台设备播放？")
            .setItems(names.toTypedArray()) { _, which ->
                val (e, avt) = renderers[which]
                pushToRenderer(avt, e.device!!.friendlyName, item)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 真正推送一首歌到指定播放器（含日志、结果、自动订阅状态） */
    private fun pushToRenderer(
        renderer: UpnpService,
        deviceName: String,
        item: MediaItem
    ) {
        val targetName = deviceName.ifEmpty { "播放器" }
        appendLog("▶ 从曲库推送 ${item.title} → $targetName\n   地址: ${item.resUrl}")
        controlExecutor.execute {
            val results = DlnaPlayer.pushAndPlay(renderer, item.resUrl, item.title)
            for ((name, r) in results) {
                mainHandler.post {
                    appendLog(
                        if (r.success) "  ✔ $name 成功"
                        else "  ✘ $name 失败: ${r.summary()}"
                    )
                }
            }
            if (results.lastOrNull()?.second?.success == true) {
                mainHandler.post {
                    Toast.makeText(this, "已推送给 $targetName 播放", Toast.LENGTH_SHORT).show()
                    if (!subscriptions.containsKey(renderer.eventSubUrl)) subscribeService(renderer)
                }
            }
        }
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
        Log.d(
            TAG,
            "[SOAP>] ${service.serviceType}#${action.name} @ ${service.controlUrl} args=$args"
        )
        controlExecutor.execute {
            val result = SoapCaller.call(service.controlUrl, service.serviceType, action.name, args)
            mainHandler.post {
                if (result.success) {
                    Log.i(
                        TAG,
                        "[SOAP<] ${action.name} OK http=${result.httpCode} @ ${service.controlUrl}"
                    )
                } else {
                    Log.e(
                        TAG,
                        "[SOAP!] ${action.name} 失败 ${result.summary()} @ ${service.controlUrl} " +
                            "body=${result.body.take(300)}"
                    )
                }
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
        Log.d(TAG, "[GENA>] SUBSCRIBE $eventUrl CALLBACK=$callbackUrl")
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
                    Log.i(TAG, "[GENA<] 订阅成功 ${sub.serviceName} SID=${result.sid}")
                    appendLog("<< 订阅成功 SID=${result.sid} TIMEOUT=${sub.timeoutSec}s，已安排自动续订")
                    scheduleRenewal(eventUrl)
                } else {
                    Log.w(TAG, "[GENA!] 订阅失败 $eventUrl: ${result.message}")
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
        Log.d(TAG, "[GENA] RENEW ${sub.serviceName} $eventUrl SID=${sub.sid}")
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
        Log.d(TAG, "[GENA] UNSUBSCRIBE ${sub.serviceName} SID=${sub.sid}")
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
        Log.d(TAG, "[EVENT] from=$remote sid=$sid nts=$nts body=${body.take(400)}")
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
    // 历史说明：这里曾是"缓冲 + 定时批量刷新到 TextView"的 UI 日志。
    // UI 日志区已在第 6 课后移除（设备多时看不过来），排查请用 logcat：
    //     adb logcat -s MyUPNP
    // appendLog 保留空实现，是为了不逐个改几十处历史调用点。
    // ------------------------------------------------------------------
    @Suppress("unused")
    private fun appendLog(line: String) {
        // UI 日志已移除：统一走 logcat（关键节点均已打点）
    }

    companion object {
        /** 填参数时预填的常见默认值（DLNA 媒体设备几乎都长这样） */
        private val DEFAULT_ARGS = mapOf(
            "InstanceID" to "0",   // AVTransport/RenderingControl 的实例号
            "Speed" to "1",        // Play 的播放速度
            "Channel" to "Master"  // RenderingControl 的音量通道
        )

        /** logcat 统一 TAG：adb logcat -s MyUPNP 过滤 */
        private const val TAG = "MyUPNP"
    }
}
