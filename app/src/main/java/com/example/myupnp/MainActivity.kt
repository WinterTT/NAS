package com.example.myupnp

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.myupnp.device.ScpdLoader
import com.example.myupnp.dlna.ContentDirectoryClient
import com.example.myupnp.dlna.DlnaPlayer
import com.example.myupnp.model.MediaContainer
import com.example.myupnp.model.MediaItem
import com.example.myupnp.model.UpnpAction
import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.model.UpnpService
import com.example.myupnp.soap.SoapCaller
import com.example.myupnp.ssdp.SsdpDiscovery
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.URL

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

    /** MVVM：ViewModel 持有 UI 状态（扫描/设备数/正在播放摘要） */
    private val vm: MainViewModel by viewModels()

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceTitle: TextView
    private lateinit var listDevices: ListView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // ===== 第 7 课 A：底部"正在播放"控制条视图 =====
    private lateinit var nowPlayingBar: View
    private lateinit var tvNowDevice: TextView
    private lateinit var tvNowTitle: TextView
    private lateinit var btnNowPlayPause: ImageButton
    private lateinit var btnNowStop: ImageButton
    private lateinit var btnNowVolDown: ImageButton
    private lateinit var btnNowVolUp: ImageButton
    private lateinit var seekNow: android.widget.SeekBar
    private lateinit var tvNowTime: TextView
    private lateinit var tvNowDuration: TextView
    private lateinit var tvNowVolume: TextView

    // ===== 第 7 课 B：播放队列控件（上一首/下一首 + 队列入口） =====
    private lateinit var btnNowPrev: ImageButton
    private lateinit var btnNowNext: ImageButton
    private lateinit var tvNowQueue: TextView
    private lateinit var tvQueueEntry: TextView

    /** 用户正在拖动进度条（避免 ticker 抢进度） */
    private var seekDragging = false

    // ===== MVVM：对象与线程池全部归 ViewModel，Activity 只引用 =====
    private val mainHandler: Handler get() = vm.mainHandler
    private val fetchExecutor get() = vm.fetchExecutor
    private val controlExecutor get() = vm.controlExecutor
    private val registry: DeviceRegistry get() = vm.registry
    private val nowSession: NowPlayingSession get() = vm.nowSession
    private val subManager: SubscriptionManager get() = vm.subManager
    private val discovery: SsdpDiscovery get() = vm.discovery

    private val deviceItems = ArrayList<DeviceListItem>()
    private lateinit var adapter: DeviceListAdapter

    // ------------------------------------------------------------------
    // A2：Wi-Fi 变化 —— 系统回调在此注册/注销（生命周期绑定），
    // 决策逻辑在 ViewModel（vm.onWifi*）。只关心 TRANSPORT_WIFI。
    // ------------------------------------------------------------------

    /** 只关心 Wi-Fi 网络的请求过滤器 */
    private val wifiNetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post { vm.onWifiAvailable() }
        }

        override fun onLost(network: Network) {
            mainHandler.post { vm.onWifiLost() }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Wi-Fi 的 IP/DNS 等能力就绪或变化时触发，是拿"新 IP"的信号
            mainHandler.post {
                val ip = wifiIpv4Of(network)
                if (ip != null) vm.onWifiIpReady(ip)
            }
        }
    }

    /** 从 Network 的 linkProperties 里取非回环 IPv4（该 Wi-Fi 的真实 IP） */
    private fun wifiIpv4Of(network: Network): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    // ------------------------------------------------------------------
    // 第 3 课：GENA 事件
    // 回调服务器 + 订阅管理器都收进 ViewModel（见 MainViewModel.eventServer/
    // subManager）。Activity 只通过 subManager / vm 状态做 UI。
    // ------------------------------------------------------------------

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
        tvQueueEntry = findViewById(R.id.tvQueueEntry)
        listDevices = findViewById(R.id.listDevices)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        adapter = DeviceListAdapter(this, deviceItems)
        listDevices.adapter = adapter

        // ---- 第 2~4 课：点设备 -> 选择场景（播放器/服务） ----
        listDevices.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as? DeviceListItem.DeviceItem
                ?: return@setOnItemClickListener // 分组标题不可点
            val entry = registry[item.entryKey] ?: return@setOnItemClickListener
            val device = entry.device
            if (device == null) {
                Toast.makeText(this, "设备描述还没加载成功，无法操作", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            showDeviceActions(entry, device)
        }
        // 第 7 课 E：长按设备 -> 设备管理（收藏 / 重命名）
        listDevices.setOnItemLongClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as? DeviceListItem.DeviceItem
                ?: return@setOnItemLongClickListener false
            val entry = registry[item.entryKey] ?: return@setOnItemLongClickListener false
            showDeviceManageDialog(entry, entry.device)
            true
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
            vm.clearAllDevices()
        }

        // ===== 第 7 课 A：底部"正在播放"控制条 =====
        nowPlayingBar = findViewById(R.id.nowPlayingBar)
        tvNowDevice = findViewById(R.id.tvNowDevice)
        tvNowTitle = findViewById(R.id.tvNowTitle)
        btnNowPlayPause = findViewById(R.id.btnNowPlayPause)
        btnNowStop = findViewById(R.id.btnNowStop)
        btnNowVolDown = findViewById(R.id.btnNowVolDown)
        btnNowVolUp = findViewById(R.id.btnNowVolUp)
        seekNow = findViewById(R.id.seekNow)
        tvNowTime = findViewById(R.id.tvNowTime)
        tvNowDuration = findViewById(R.id.tvNowDuration)
        tvNowVolume = findViewById(R.id.tvNowVolume)
        btnNowPrev = findViewById(R.id.btnNowPrev)
        btnNowNext = findViewById(R.id.btnNowNext)
        tvNowQueue = findViewById(R.id.tvNowQueue)

        btnNowPlayPause.setOnClickListener { togglePlayPause() }
        btnNowStop.setOnClickListener { stopNowPlaying() }
        btnNowVolDown.setOnClickListener { volumeStepNow(-10) }
        btnNowVolUp.setOnClickListener { volumeStepNow(10) }
        // 第 7 课 B：播放队列
        btnNowPrev.setOnClickListener { vm.queuePreviousItem() }
        btnNowNext.setOnClickListener { vm.queueNextItem() }
        tvNowQueue.setOnClickListener { showQueueDialog() }
        // 首页常驻队列入口（没在播放也能点开队列）
        tvQueueEntry.setOnClickListener { showQueueDialog() }

        // 进度条：拖动中不更新（ticker 停手），松手发 Seek
        seekNow.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvNowTime.text = fmtDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {
                seekDragging = true
            }

            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {
                seekDragging = false
                val dur = vm.uiState.value.durationSec
                if (dur > 0) {
                    val target = bar?.progress?.toLong()?.coerceIn(0, dur) ?: return
                    nowSession.seekTo(target)
                }
            }
        })

        // ===== MVVM：UI 观察 ViewModel 状态（第 1+2 批） =====
        // 通用 UI 状态：扫描按钮/状态栏/计数/正在播放控制条
        lifecycleScope.launch {
            vm.uiState.collect { s ->
                // 扫描按钮可用性 & 状态栏
                btnStart.isEnabled = !s.scanning
                btnStop.isEnabled = s.scanning
                if (s.statusOverride != null) {
                    tvStatus.text = s.statusOverride
                } else if (s.statusText != null) {
                    tvStatus.setText(s.statusText)
                }
                // 设备标题计数
                tvDeviceTitle.text =
                    getString(R.string.device_title) + "  (${s.deviceCount})" +
                        if (s.deviceCount > 0) "　长按=收藏/重命名" else ""
                // 首页队列入口：队列有内容才显示（没在播放也能点开）
                if (s.queueHasItems) {
                    tvQueueEntry.visibility = View.VISIBLE
                    tvQueueEntry.text = buildString {
                        append("播放队列：")
                        if (s.queuePendingCount > 0) append("待播 ${s.queuePendingCount} 首，")
                        if (s.queueCurrentTitle.isNotEmpty()) append("当前《${s.queueCurrentTitle}》")
                        else append("当前无")
                        append(" —— 点此查看/切歌")
                    }
                } else {
                    tvQueueEntry.visibility = View.GONE
                }
                // 正在播放控制条
                if (s.nowPlayingActive) {
                    nowPlayingBar.visibility = View.VISIBLE
                    tvNowDevice.text = s.nowPlayingDevice
                    tvNowTitle.text = s.nowPlayingTitle
                    btnNowPlayPause.setImageResource(
                        if (s.nowPlayingPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    btnNowVolDown.isEnabled = s.nowPlayingHasRc
                    btnNowVolUp.isEnabled = s.nowPlayingHasRc
                    val volAlpha = if (s.nowPlayingHasRc) 1.0f else 0.3f
                    btnNowVolDown.alpha = volAlpha
                    btnNowVolUp.alpha = volAlpha
                    // 进度：不拖动时才跟随状态，避免与用户拖拽打架
                    if (!seekDragging) {
                        val dur = if (s.durationSec > 0) s.durationSec else 1
                        seekNow.max = dur.toInt()
                        seekNow.progress = s.positionSec.coerceIn(0, s.durationSec).toInt()
                    }
                    tvNowTime.text = fmtDuration(s.positionSec)
                    tvNowDuration.text = fmtDuration(s.durationSec)
                    seekNow.isEnabled = s.seekable && !s.nowPlayingDevice.isEmpty()
                    // 音量显示（未知显示 --）
                    tvNowVolume.text = s.volume?.toString() ?: "--"
                    tvNowVolume.visibility = if (s.nowPlayingHasRc) View.VISIBLE else View.GONE
                    // 播放队列：待播几首显示在入口上
                    tvNowQueue.text = "队列(${s.queuePendingCount})"
                } else {
                    nowPlayingBar.visibility = View.GONE
                }
            }
        }
        // 设备列表行（由 registry.listener → VM.deviceRows 驱动）
        lifecycleScope.launch {
            vm.deviceRows.collect { rows ->
                deviceItems.clear()
                deviceItems.addAll(rows)
                adapter.notifyDataSetChanged()
            }
        }
        // 一次性消息（订阅/退订等提示）
        lifecycleScope.launch {
            vm.messages.collect { msg ->
                Toast.makeText(
                    this@MainActivity, msg.text,
                    if (msg.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }
        }

        Log.i(TAG, "[UI] MyUPNP 启动完成，等待用户操作")

        // A2：只监听 Wi-Fi 网络（蜂窝/5G 变化不会误触发）
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(wifiNetworkRequest, networkCallback)
            Log.i(TAG, "[NET] 已注册 Wi-Fi 网络监听")
        }.onFailure {
            Log.w(TAG, "[NET] 注册网络监听失败: ${it.message}")
        }
    }

    // ------------------------------------------------------------------
    // 扫描控制（实现都在 ViewModel；这里只处理"权限 + 转发"）
    // ------------------------------------------------------------------
    private fun startScanningInternal() {
        vm.startScan()
    }

    private fun stopScanning() {
        vm.stopScan()
    }

    override fun onDestroy() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        }
        vm.onHostDestroyed()
        // executor 由 MainViewModel.onCleared 统一释放
        super.onDestroy()
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
                .setTitle(vm.shownNameOf(entry))
                .setItems(
                    arrayOf(
                        "▶ 播放器场景（推送 URL 播放）",
                        "服务控制 / 订阅（SOAP + GENA）",
                        "⚙ 设备管理（收藏 / 重命名）"
                    )
                ) { _, which ->
                    when (which) {
                        0 -> showPlayerPanel(
                            deviceName = device.friendlyName.ifEmpty { "播放器" },
                            renderer = renderer,
                            rc = rc,
                            device = device,
                            deviceKey = entry.location
                        )
                        1 -> showServicePicker(entry, device)
                        2 -> showDeviceManageDialog(entry, device)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (DeviceRegistry.isMediaServer(device)) {
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
                menus += "⚙ 设备管理（收藏 / 重命名）"
                actions += { showDeviceManageDialog(entry, device) }
                AlertDialog.Builder(this)
                    .setTitle(vm.shownNameOf(entry))
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

    /** 设备管理：收藏 / 重命名 / 恢复原名（长按设备行或从设备菜单进） */
    private fun showDeviceManageDialog(entry: Entry, device: UpnpDevice?) {
        val alias = vm.bookmarks.alias(vm.deviceIdOf(entry))
        val favorite = vm.isFavoriteEntry(entry)
        val options = mutableListOf(
            if (favorite) "取消收藏（从置顶移除 ⭐）" else "⭐ 收藏（列表置顶）",
            if (alias == null) "重命名（设置别名）" else "重命名（当前别名：$alias）"
        )
        if (alias != null) options += "恢复原名"
        AlertDialog.Builder(this)
            .setTitle(vm.shownNameOf(entry))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> vm.toggleFavorite(entry.location)
                    1 -> showRenameDialog(entry, device, alias)
                    2 -> if (alias != null) vm.clearDeviceAlias(entry.location)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(entry: Entry, device: UpnpDevice?, currentAlias: String?) {
        val input = EditText(this).apply {
            hint = device?.friendlyName?.ifEmpty { null } ?: "例如：客厅音响"
            setText(currentAlias.orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名设备")
            .setMessage("只改 App 里的显示名，不影响设备本身")
            .setView(input)
            .setPositiveButton("保存") { _, _ -> vm.renameDevice(entry.location, input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 曲库浏览器：常驻对话框，从根开始逐层 Browse。
     * 点文件夹进入下一层、点歌曲弹"播放/加入队列"——但对话框始终不关，
     * 可以一口气连点好多首排队，不用反复重进曲库。
     */
    private fun showMediaBrowser(cds: UpnpService) {
        // 浏览状态：当前层 objectId + 面包屑 (id, 标题) 栈（最右为当前层）
        var objectId = ContentDirectoryClient.ROOT_OBJECT_ID
        val crumb = ArrayList<Pair<String, String>>()

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val btnBack = Button(this).apply { text = "返回上级" }
        val btnClose = Button(this).apply { text = "关闭浏览器" }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnBack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnClose, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(listView)
            addView(bottom)
        }

        // 一个可变适配器：目录/歌曲/状态行都往里面填
        val labels = ArrayList<String>()
        val rowActions = ArrayList<() -> Unit>()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = listAdapter

        fun fillLoading(text: String) {
            labels.clear(); rowActions.clear()
            labels.add(text); rowActions.add {}
            listAdapter.notifyDataSetChanged()
        }

        fun reload() {
            val dirName = crumb.lastOrNull()?.second ?: "媒体库"
            appendLog(">> Browse $dirName ($objectId) @ ${cds.controlUrl}")
            Log.d(TAG, "[CDS>] Browse objectId=$objectId ctrl=${cds.controlUrl}")
            fillLoading("正在加载…")
            btnBack.isEnabled = crumb.isNotEmpty()
            controlExecutor.execute {
                val out = ContentDirectoryClient.browse(cds, objectId)
                mainHandler.post {
                    if (!out.ok) {
                        Log.e(TAG, "[CDS!] Browse 失败 objectId=$objectId: ${out.error}")
                        appendLog("!! Browse 失败: ${out.error}")
                        if (out.rawSnippet.isNotBlank()) appendLog("   响应片段: ${out.rawSnippet}")
                        fillLoading("Browse 失败：${out.error}")
                        return@post
                    }
                    if (out.objects.isEmpty()) {
                        appendLog("  该目录为空（没有子项）")
                        fillLoading("（这个目录是空的）")
                        return@post
                    }
                    Log.i(
                        TAG,
                        "[CDS<] Browse 返回 ${out.objects.size} 项: " +
                            out.objects.joinToString(", ") { "${it.title}[${if (it.isContainer) "dir" else "file"}]" }
                    )
                    // 组装这一层的可点行
                    labels.clear()
                    rowActions.clear()
                    for (obj in out.objects) {
                        labels.add(obj.displayText())
                        if (obj is MediaContainer) {
                            rowActions.add {
                                crumb.add(obj.id to obj.title)
                                objectId = obj.id
                                reload()
                            }
                        } else if (obj is MediaItem) {
                            rowActions.add { mediaItemOptions(obj) }
                        } else {
                            rowActions.add {}
                        }
                    }
                    listAdapter.notifyDataSetChanged()
                }
            }
        }

        btnBack.setOnClickListener {
            if (crumb.isEmpty()) {
                Toast.makeText(this, "已经在根目录了", Toast.LENGTH_SHORT).show()
            } else {
                val parent = crumb.removeAt(crumb.size - 1)
                objectId = parent.first
                reload()
            }
        }

        listView.setOnItemClickListener { _, _, which, _ ->
            rowActions.getOrNull(which)?.invoke()
        }

        AlertDialog.Builder(this)
            .setTitle("媒体库（点歌排队，可连续操作）")
            .setView(panel)
            .setNegativeButton("关闭", null)
            .show()
        reload()
    }

    /** 曲库里点一首歌：先问"立即播放 / 加入队列"，再决定走哪条路（浏览器不关闭） */
    private fun mediaItemOptions(item: MediaItem) {
        if (item.resUrl.isBlank()) {
            Toast.makeText(this, "该条目没有可播放地址(res)", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(
                arrayOf(
                    "立即播放",
                    "加入队列（播完自动连播）"
                )
            ) { _, which ->
                when (which) {
                    0 -> playMediaItemFromServer(item)
                    1 -> vm.queueEnqueue(item)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 曲库里点歌：弹出"选设备播放"框（可刷新设备列表，设备没找到也能重扫） */
    private fun playMediaItemFromServer(item: MediaItem) {
        if (item.resUrl.isBlank()) {
            Toast.makeText(this, "该条目没有可播放地址(res)", Toast.LENGTH_SHORT).show()
            return
        }

        // 对话框里的数据源：发现到的播放器 (设备, AVTransport 服务)
        val pairs = mutableListOf<Pair<Entry, UpnpService>>()
        val names = mutableListOf<String>()
        var listAdapter: ArrayAdapter<String>? = null

        fun collectRenderers() {
            pairs.clear()
            names.clear()
            // 收藏的设备排前面，显示用别名（第 7 课 E）
            for (e in vm.deviceEntriesFavoritesFirst()) {
                val d = e.device ?: continue
                val avt = DlnaPlayer.avTransportOf(d) ?: continue
                pairs.add(e to avt)
                val star = if (vm.isFavoriteEntry(e)) "⭐ " else ""
                val tag = "$star📺 ${vm.shownNameOf(e)}  ${d.modelName}"
                names.add(tag)
            }
        }

        fun pushSelected(index: Int) {
            val (e, avt) = pairs[index]
            pushToRenderer(
                renderer = avt,
                deviceName = e.device!!.friendlyName,
                rc = e.device!!.let { DlnaPlayer.renderingControlOf(it) },
                item = item,
                device = e.device!!,
                deviceKey = e.location
            )
        }

        // 刷新动作：触发引擎立即 M-SEARCH，1.5s 后（MX 内应答回到注册表）重取列表
        fun refresh() {
            vm.refreshDevicesNow()
            Toast.makeText(this, "正在搜索设备…", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({
                collectRenderers()
                listAdapter?.notifyDataSetChanged()
                if (pairs.isEmpty()) {
                    Toast.makeText(this, "仍没找到可播放设备", Toast.LENGTH_SHORT).show()
                }
            }, 1_500L)
        }

        // ---- 自绘对话框：可刷新 ListView + 底部按钮 ----
        collectRenderers()

        // 单台设备时：不弹框，直接推（原行为），避免打断
        if (pairs.size == 1) {
            pushSelected(0)
            return
        }

        // 空/多台都弹同一个框：空时列表空，靠"刷新"按钮找设备
        // 注意：ListView 必须占固定权重高度（不能 wrap_content），
        // 否则设备一多会把下方"刷新"按钮挤出对话框可视区。
        val btnRefresh = Button(this).apply { text = "🔄 刷新设备列表" }
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f // weight=1：占满可用空间，按钮始终钉在底部
            )
        }
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.adapter = listAdapter

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 8, 30, 4)
            // 固定整个面板高度，避免 dialog 被列表撑满
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                px(460)
            )
            addView(listView)
            addView(btnRefresh)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("推送给哪台设备播放？")
            .setView(panel)
            .setNegativeButton("取消", null)
            .show()
        listView.setOnItemClickListener { _, _, which, _ -> pushSelected(which); dialog.dismiss() }
        btnRefresh.setOnClickListener { refresh() }
    }

    /** 队列总览：正在播 + 接下来 N 首；点待播一首 = 立即切到它 */
    private fun showQueueDialog() {
        val queue = vm.playbackQueue
        val pending = vm.queuePendingSnapshot()
        val rows = ArrayList<String>()
        val actions = ArrayList<(() -> Unit)?>()

        val cur = queue.current
        when {
            cur == null -> Unit
            nowSession.isActive -> {
                rows += "▶ 正在播放：《${cur.title}》"
                actions += null
            }
            else -> {
                rows += "▶ 当前：《${cur.title}》（还没开播/播放目标已不在）"
                actions += null
            }
        }
        if (pending.isEmpty()) {
            rows += if (queue.hasActivity) "—— 没有待播的了 ——"
            else "队列是空的：去曲库点歌选「加入队列」"
            actions += null
        } else {
            if (!nowSession.isActive) {
                rows += "（尚未开播：点下面的歌会先让你选播放设备）"
                actions += null
            }
            for ((i, item) in pending.withIndex()) {
                rows += "${i + 1}. ${item.title}"
                actions += { playQueueRow(item, i) }
            }
        }
        if (queue.historyCount > 0) {
            rows += "…已播 ${queue.historyCount} 首（控制条「上一首」可回放）"
            actions += null
        }
        AlertDialog.Builder(this)
            .setTitle("播放队列")
            .setItems(rows.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNeutralButton("清空队列") { _, _ -> vm.queueClear() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 队列里点一首：有播放目标就直接切过去；还没有就先走"选设备播放" */
    private fun playQueueRow(item: MediaItem, indexInQueue: Int) {
        if (nowSession.isActive) {
            vm.queuePlayPendingAt(indexInQueue)
        } else {
            Toast.makeText(this, "还没有播放目标：请选择这首歌播到哪台设备", Toast.LENGTH_SHORT).show()
            playMediaItemFromServer(item) // 选设备推送成功后自动记成"当前"，播完接续连播
        }
    }

    /** 真正推送一首歌到指定播放器（含日志、结果、自动订阅 + 显示控制条） */
    private fun pushToRenderer(
        renderer: UpnpService,
        deviceName: String,
        rc: UpnpService?,
        item: MediaItem,
        device: UpnpDevice,
        deviceKey: String
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
                    setNowPlaying(deviceName, renderer, rc, item.title, deviceKey)
                    // 记入播放队列的"当前这首"（排队的歌会在播完后自动接上）
                    vm.queueOnPlayed(item)
                    Toast.makeText(this, "已推送给 $targetName 播放", Toast.LENGTH_SHORT).show()
                    // 操作了这台设备 -> 自动订阅其全部服务（之后别处操作也能同步）
                    vm.activateDeviceSubscription(deviceKey, device.services)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 第 7 课 A：底部"正在播放"控制条
    // ------------------------------------------------------------------

    /** 建立播放会话（逻辑在 NowPlayingSession，成功后控制条自动出现） */
    private fun setNowPlaying(
        deviceName: String,
        avt: UpnpService,
        rc: UpnpService?,
        title: String,
        deviceKey: String? = null
    ) {
        nowSession.begin(deviceName, avt, rc, title, deviceKey = deviceKey)
    }

    /** 播放/暂停切换 → 转发给会话 */
    private fun togglePlayPause() = nowSession.toggle()

    /** 停止播放（保留会话，控制条不消失）→ 转发给会话 */
    private fun stopNowPlaying() = nowSession.stop()

    /**
     * 若正在播放的设备已从发现列表消失（下线/心跳超时/清空），
     * 自动收起控制条，避免留下指向死设备的控制条。
     */
    private fun clearNowPlayingIfDeviceGone() {
        if (!nowSession.isActive) return
        val playingHost = nowSession.playingHost()
        val stillAlive = registry.all().any { e ->
            val h = runCatching { URL(e.location).host }.getOrNull()
            h == playingHost
        }
        if (!stillAlive) nowSession.end("设备已离线")
    }

    /** 音量步进（±10），作用在保存的 RenderingControl 服务 */
    private fun volumeStepNow(delta: Int) {
        val np = nowSession.current ?: return
        val rc = np.rc ?: run {
            Toast.makeText(this, "该设备没有 RenderingControl（音量不可调）", Toast.LENGTH_SHORT).show()
            return
        }
        stepVolume(rc, delta)
    }

    /**
     * 播放器面板：输入一个媒体 URL，就能让电视/音箱播起来。
     * 全流程复用前三课：SOAP 控制(SetAVTransportURI/Play/音量) + GENA 订阅(看进度)。
     */
    private fun showPlayerPanel(
        deviceName: String,
        renderer: UpnpService,
        rc: UpnpService?,
        device: UpnpDevice,
        deviceKey: String
    ) {
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
                // 推送成功后：建立播放会话 + 自动订阅该设备全部服务
                if (results.lastOrNull()?.second?.success == true) {
                    mainHandler.post {
                        setNowPlaying(
                            deviceName = deviceName,
                            avt = renderer,
                            rc = rc,
                            title = url.substringAfterLast('/').ifEmpty { url },
                            deviceKey = deviceKey
                        )
                        vm.activateDeviceSubscription(deviceKey, device.services)
                    }
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
                    // 若步进的就是当前播放会话的设备，同步到控制条显示
                    if (after >= 0 && nowSession.current?.rc?.controlUrl == rc.controlUrl) {
                        nowSession.syncVolume(after)
                    }
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
        val subscribed = subManager.isSubscribed(service.eventSubUrl)
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

    /** 秒 -> "m:ss" 显示（进度条两侧） */
    private fun fmtDuration(totalSec: Long): String {
        if (totalSec <= 0) return "0:00"
        val m = totalSec / 60
        val s = totalSec % 60
        return "$m:$s"
    }

    /** dp -> px（构建自定义对话框面板尺寸用） */
    private fun px(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // 第 3 课：GENA 事件订阅
    // 实现已抽到 SubscriptionManager；这里只保留薄转发层 + 回调地址
    // ------------------------------------------------------------------

    /** 订阅一个服务的事件推送 */
    private fun subscribeService(service: UpnpService) {
        val callbackUrl = eventCallbackUrl()
        if (callbackUrl == null) {
            Toast.makeText(this, "无法确定本机回调地址，订阅失败", Toast.LENGTH_LONG).show()
            return
        }
        subManager.subscribe(service, callbackUrl)
    }

    /** 手动续订 */
    private fun renewSubscription(eventUrl: String) = subManager.renew(eventUrl)

    /** 退订 */
    private fun unsubscribeService(service: UpnpService) = subManager.unsubscribe(service)

    /** 构造本机回调地址（事件服务器在 VM 内管理） */
    private fun eventCallbackUrl(): String? = vm.eventCallbackUrl

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
