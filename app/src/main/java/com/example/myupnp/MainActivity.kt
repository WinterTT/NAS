package com.example.myupnp

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.OpenableColumns
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
import java.net.HttpURLConnection
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
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // ===== 三页结构（媒体库 / 播放 / 设置）+ 底部导航 =====
    private lateinit var pageLibrary: View
    private lateinit var pagePlay: View
    private lateinit var pageSettings: View
    private lateinit var pageSearch: View
    private lateinit var btnSearchEntry: TextView
    private lateinit var btnSearchBack: Button
    private lateinit var etSearch: android.widget.EditText
    private lateinit var tvSearchStatus: TextView
    private lateinit var tvSearchEmpty: TextView
    private lateinit var listSearchResults: ListView
    private lateinit var btnSearchServer: Button
    private lateinit var btnBuildIndex: Button
    private lateinit var searchAdapter: android.widget.BaseAdapter
    private val searchItems = ArrayList<MediaItem>()
    private var indexDialog: AlertDialog? = null
    private lateinit var tvSettingsInfo: TextView

    // ===== 服务器曲库（索引后的分类浏览） =====
    private lateinit var pageServer: View
    private lateinit var btnServerBack: Button
    private lateinit var btnServerBrowse: Button
    private lateinit var tvServerTitle: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvServerEmpty: TextView
    private lateinit var listServer: ListView
    private lateinit var scopeRow: View
    private lateinit var btnTabAlbums: Button
    private lateinit var btnTabArtists: Button
    private lateinit var btnTabSongs: Button
    private lateinit var btnPlayAll: Button
    private lateinit var btnQueueAll: Button
    private lateinit var serverAdapter: android.widget.BaseAdapter

    /** 分类列表的一行：分组（专辑/歌手）或歌曲 */
    private sealed class ServerRow {
        data class Group(val title: String, val sub: String) : ServerRow()
        data class Song(val item: MediaItem) : ServerRow()
    }

    private val serverRows = ArrayList<ServerRow>()
    private var serverEntry: Entry? = null
    private var serverCds: UpnpService? = null

    /** 0=顶层分类（音乐/视频/图片…） 1=分类内（专辑/歌手/歌曲） 2=某专辑/某歌手详情 */
    private var serverStage = 0

    /** 当前分类（null = 全部分类/全部媒体） */
    private var serverCategoryId: String? = null
    private var serverCategoryTitle = "全部媒体"
    private val categoryTargets = ArrayList<MediaIndexStore.CategoryRow?>()

    /** 0=专辑 1=歌手 2=歌曲 */
    private var serverMode = 0

    /** 二级过滤：非 null 表示正在看"某专辑/某歌手"的歌曲列表 */
    private var serverFilter: String? = null
    private lateinit var bottomNav: com.google.android.material.bottomnavigation.BottomNavigationView
    private var lastTabBeforePlay = R.id.nav_library
    private var currentTabId = R.id.nav_library

    // ===== 正在播放：完整页 + 迷你条共用字段 =====
    private lateinit var nowPlayingBar: View        // 播放页里的内容面板
    private lateinit var tvPlayEmpty: TextView      // 播放页空态
    private lateinit var miniNowBar: View           // 迷你播放条（跨页）
    private lateinit var miniArt: android.widget.ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniMeta: TextView
    private lateinit var btnMiniPlay: ImageButton
    private lateinit var btnMiniNext: ImageButton
    private lateinit var imgGlow: android.widget.ImageView // 播放页背景氛围
    private lateinit var tvNowDevice: TextView
    private lateinit var tvNowTitle: TextView
    private lateinit var btnNowPlayPause: ImageButton
    private lateinit var btnNowStop: ImageButton
    private lateinit var seekNow: android.widget.SeekBar
    private lateinit var seekVol: android.widget.SeekBar
    private lateinit var volRow: View
    private lateinit var tvNowTime: TextView
    private lateinit var tvNowDuration: TextView
    private lateinit var tvNowVolume: TextView
    private lateinit var btnNowPrev: ImageButton
    private lateinit var btnNowNext: ImageButton
    private lateinit var tvNowQueue: TextView
    private lateinit var btnPushLocal: Button
    private lateinit var btnQueueQuick: Button
    private lateinit var btnRecentQuick: Button
    private lateinit var imgNowArt: android.widget.ImageView
    private lateinit var tvNowMeta: TextView
    private var lastArtUrl: String? = null // 已加载封面的地址（避免重复下载）

    // ===== 媒体库页（媒体服务器列表） =====
    private lateinit var listLibrary: ListView
    private lateinit var tvLibEmpty: TextView
    private val libraryServers = ArrayList<Pair<Entry, com.example.myupnp.model.UpnpService>>()

    /** 用户正在拖动进度条 / 音量滑杆（避免 ticker/事件回写抢进度） */
    private var seekDragging = false
    private var volDragging = false

    /** 媒体库页服务器列表的 adapter */
    private lateinit var libraryAdapter: android.widget.BaseAdapter

    // ===== MVVM：对象与线程池全部归 ViewModel，Activity 只引用 =====
    private val mainHandler: Handler get() = vm.mainHandler
    private val fetchExecutor get() = vm.fetchExecutor
    private val controlExecutor get() = vm.controlExecutor
    private val registry: DeviceRegistry get() = vm.registry
    private val nowSession: NowPlayingSession get() = vm.nowSession
    private val subManager: SubscriptionManager get() = vm.subManager
    private val discovery: SsdpDiscovery get() = vm.discovery

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

    // 第 7 课 B：选手机里的本地媒体文件
    private val localFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { pushLocalMedia(it) }
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
        btnPushLocal = findViewById(R.id.btnPushLocal)
        btnQueueQuick = findViewById(R.id.btnQueueQuick)
        btnRecentQuick = findViewById(R.id.btnRecentQuick)
        tvSettingsInfo = findViewById(R.id.tvSettingsInfo)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

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

        // ===== 三页 + 底部导航 + 播放页/迷你条 视图 =====
        pageLibrary = findViewById(R.id.pageLibrary)
        pagePlay = findViewById(R.id.pagePlay)
        pageSettings = findViewById(R.id.pageSettings)
        pageSearch = findViewById(R.id.pageSearch)
        btnSearchEntry = findViewById(R.id.btnSearchEntry)
        btnSearchBack = findViewById(R.id.btnSearchBack)
        etSearch = findViewById(R.id.etSearch)
        tvSearchStatus = findViewById(R.id.tvSearchStatus)
        tvSearchEmpty = findViewById(R.id.tvSearchEmpty)
        listSearchResults = findViewById(R.id.listSearchResults)
        btnSearchServer = findViewById(R.id.btnSearchServer)
        btnBuildIndex = findViewById(R.id.btnBuildIndex)
        pageServer = findViewById(R.id.pageServer)
        btnServerBack = findViewById(R.id.btnServerBack)
        btnServerBrowse = findViewById(R.id.btnServerBrowse)
        tvServerTitle = findViewById(R.id.tvServerTitle)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvServerEmpty = findViewById(R.id.tvServerEmpty)
        listServer = findViewById(R.id.listServer)
        scopeRow = findViewById(R.id.scopeRow)
        btnTabAlbums = findViewById(R.id.btnTabAlbums)
        btnTabArtists = findViewById(R.id.btnTabArtists)
        btnTabSongs = findViewById(R.id.btnTabSongs)
        btnPlayAll = findViewById(R.id.btnPlayAll)
        btnQueueAll = findViewById(R.id.btnQueueAll)
        bottomNav = findViewById(R.id.bottomNav)
        nowPlayingBar = findViewById(R.id.nowPlayingBar)
        tvPlayEmpty = findViewById(R.id.tvPlayEmpty)
        miniNowBar = findViewById(R.id.miniNowBar)
        miniArt = findViewById(R.id.miniArt)
        miniTitle = findViewById(R.id.miniTitle)
        miniMeta = findViewById(R.id.miniMeta)
        btnMiniPlay = findViewById(R.id.btnMiniPlay)
        btnMiniNext = findViewById(R.id.btnMiniNext)
        imgGlow = findViewById(R.id.imgGlow)
        tvNowDevice = findViewById(R.id.tvNowDevice)
        tvNowTitle = findViewById(R.id.tvNowTitle)
        btnNowPlayPause = findViewById(R.id.btnNowPlayPause)
        btnNowStop = findViewById(R.id.btnNowStop)
        seekNow = findViewById(R.id.seekNow)
        seekVol = findViewById(R.id.seekVol)
        volRow = findViewById(R.id.volRow)
        tvNowTime = findViewById(R.id.tvNowTime)
        tvNowDuration = findViewById(R.id.tvNowDuration)
        tvNowVolume = findViewById(R.id.tvNowVolume)
        btnNowPrev = findViewById(R.id.btnNowPrev)
        btnNowNext = findViewById(R.id.btnNowNext)
        tvNowQueue = findViewById(R.id.tvNowQueue)
        imgNowArt = findViewById(R.id.imgNowArt)
        tvNowMeta = findViewById(R.id.tvNowMeta)
        listLibrary = findViewById(R.id.listLibrary)
        tvLibEmpty = findViewById(R.id.tvLibEmpty)

        // 播放/暂停、停止
        btnNowPlayPause.setOnClickListener { togglePlayPause() }
        btnNowStop.setOnClickListener { stopNowPlaying() }
        btnMiniPlay.setOnClickListener { togglePlayPause() }
        btnMiniNext.setOnClickListener { vm.queueNextItem() }
        // 切歌
        btnNowPrev.setOnClickListener { vm.queuePreviousItem() }
        btnNowNext.setOnClickListener { vm.queueNextItem() }
        // 队列
        tvNowQueue.setOnClickListener { showQueueDialog() }
        btnQueueQuick.setOnClickListener { showQueueDialog() }
        // 最近播放（第 7 课 D）
        btnRecentQuick.setOnClickListener { showHistoryDialog() }
        // 本地文件推送（第 7 课 B）
        btnPushLocal.setOnClickListener {
            localFileLauncher.launch(arrayOf("audio/*", "video/*", "image/*"))
        }

        // ===== 第 8 课：搜索与索引 =====
        searchAdapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = searchItems.size
            override fun getItem(p: Int): Any = searchItems[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val view = convert
                    ?: layoutInflater.inflate(R.layout.item_queue_row, parent, false)
                val item = searchItems[p]
                view.findViewById<TextView>(R.id.tvQIndex).text = (p + 1).toString()
                view.findViewById<TextView>(R.id.tvQTitle).text = item.title
                val sub = listOf(item.artist, item.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                view.findViewById<TextView>(R.id.tvQSub).text = sub.ifEmpty { item.resUrl }
                return view
            }
        }
        listSearchResults.adapter = searchAdapter
        listSearchResults.setOnItemClickListener { _, _, p, _ ->
            searchItems.getOrNull(p)?.let { playMediaItemFromServer(it) }
        }
        listSearchResults.setOnItemLongClickListener { _, _, p, _ ->
            val target = searchItems.getOrNull(p) ?: return@setOnItemLongClickListener false
            showSearchItemMenu(target)
            true
        }
        btnSearchEntry.setOnClickListener { showSearchPage(true) }
        btnSearchBack.setOnClickListener { showSearchPage(false) }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = runLocalSearch(s?.toString().orEmpty())
        })
        btnSearchServer.setOnClickListener {
            val kw = etSearch.text.toString().trim()
            if (kw.isEmpty()) {
                Toast.makeText(this, "先输入关键词", Toast.LENGTH_SHORT).show()
            } else {
                pickServerFor("在哪台服务器上搜索？") { e, cds -> serverSearch(e, cds, kw) }
            }
        }
        btnBuildIndex.setOnClickListener {
            pickServerFor("给哪台服务器建立索引？") { e, cds -> confirmIndex(e, cds) }
        }
        vm.indexProgressListener = { scanned, items, skipped, path ->
            indexDialog?.setMessage(
                "已扫目录 $scanned 个\n已收录曲目 $items 首\n跳过重复 $skipped 条\n当前：$path"
            )
            if (pageSearch.visibility == View.VISIBLE) updateSearchStatus()
        }
        vm.indexDoneListener = { _, items, reason ->
            indexDialog?.dismiss()
            indexDialog = null
            val msg = when (reason) {
                "done" -> "索引完成：共收录 $items 首"
                "stopped" -> "已停止索引：已收录 $items 首"
                else -> "索引中断：已收录 $items 首"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            updateSearchStatus()
        }

        // ===== 服务器曲库：分类浏览（专辑 / 歌手 / 歌曲） =====
        serverAdapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = serverRows.size
            override fun getItem(p: Int): Any = serverRows[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val view = convert
                    ?: layoutInflater.inflate(R.layout.item_queue_row, parent, false)
                val row = serverRows[p]
                val titleView = view.findViewById<TextView>(R.id.tvQIndex)
                val nameView = view.findViewById<TextView>(R.id.tvQTitle)
                val subView = view.findViewById<TextView>(R.id.tvQSub)
                when (row) {
                    is ServerRow.Group -> {
                        titleView.text = ""
                        nameView.text = row.title
                        subView.text = row.sub
                    }
                    is ServerRow.Song -> {
                        titleView.text = (p + 1).toString()
                        nameView.text = row.item.title
                        val sub = listOf(row.item.artist, row.item.album)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                        subView.text = sub.ifEmpty { row.item.resUrl }
                    }
                }
                return view
            }
        }
        listServer.adapter = serverAdapter
        listServer.setOnItemClickListener { _, _, p, _ ->
            // 第 0 层：点分类进入（音乐 / 视频 / 图片 / 全部）
            if (serverStage == 0) {
                val target = categoryTargets.getOrNull(p) ?: return@setOnItemClickListener
                serverCategoryId = target?.topId
                serverCategoryTitle = target?.title ?: "全部媒体"
                serverStage = 1
                serverMode = 0
                serverFilter = null
                updateServerTabs()
                refreshServerRows()
                return@setOnItemClickListener
            }
            when (val row = serverRows.getOrNull(p)) {
                is ServerRow.Group -> {
                    serverFilter = row.title
                    serverStage = 2
                    refreshServerRows()
                }
                is ServerRow.Song -> playMediaItemFromServer(row.item)
                null -> Unit
            }
        }
        listServer.setOnItemLongClickListener { _, _, p, _ ->
            when (val row = serverRows.getOrNull(p)) {
                is ServerRow.Group -> {
                    showGroupMenu(row.title)
                    true
                }
                is ServerRow.Song -> {
                    showSearchItemMenu(row.item)
                    true
                }
                null -> false
            }
        }
        btnServerBack.setOnClickListener {
            when {
                serverStage == 2 -> { // 详情 → 回到分类内列表
                    serverFilter = null
                    serverStage = 1
                    refreshServerRows()
                }
                serverStage == 1 -> { // 分类内 → 回到顶层分类
                    serverCategoryId = null
                    serverCategoryTitle = "全部媒体"
                    serverMode = 0
                    serverFilter = null
                    serverStage = 0
                    updateServerTabs()
                    refreshServerRows()
                }
                else -> closeServerPage()
            }
        }
        btnServerBrowse.setOnClickListener { serverCds?.let { showMediaBrowser(it) } }
        btnTabAlbums.setOnClickListener { switchServerMode(0) }
        btnTabArtists.setOnClickListener { switchServerMode(1) }
        btnTabSongs.setOnClickListener { switchServerMode(2) }
        btnPlayAll.setOnClickListener {
            val songs = serverPlayScopeSongs()
            if (songs.isEmpty()) {
                Toast.makeText(this, "当前范围没有曲目", Toast.LENGTH_SHORT).show()
            } else {
                playAllSongs(songs, serverPlayScopeLabel())
            }
        }
        btnQueueAll.setOnClickListener {
            val songs = serverPlayScopeSongs()
            if (songs.isEmpty()) {
                Toast.makeText(this, "当前范围没有曲目", Toast.LENGTH_SHORT).show()
            } else {
                vm.queueEnqueueAll(songs.map { it.toMediaItem() }, serverPlayScopeLabel())
            }
        }
        // 迷你条（非按钮区域）点击 -> 打开播放页
        miniNowBar.setOnClickListener { switchTab(R.id.nav_playing) }

        // 音量滑杆：拖动实时更新数字，松手发 SetVolume
        seekVol.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvNowVolume.text = progress.toString()
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {
                volDragging = true
            }

            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {
                volDragging = false
                bar?.progress?.let { sendVolume(it) }
            }
        })

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

        // 媒体库页：服务器列表（两行卡片），点开曲库浏览
        libraryAdapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = libraryServers.size
            override fun getItem(p: Int): Any = libraryServers[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val view = convert
                    ?: layoutInflater.inflate(R.layout.item_device, parent, false)
                val (entry, _) = libraryServers[p]
                val device = entry.device
                view.findViewById<TextView>(R.id.tvDeviceName).text =
                    if (vm.isFavoriteEntry(entry)) "⭐ ${vm.shownNameOf(entry)}"
                    else vm.shownNameOf(entry)
                view.findViewById<TextView>(R.id.tvDeviceSub).text = buildString {
                    append("媒体服务器")
                    device?.modelName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    if (entry.ip.isNotBlank()) append(" · ${entry.ip}")
                }
                return view
            }
        }
        listLibrary.adapter = libraryAdapter
        listLibrary.setOnItemClickListener { _, _, p, _ ->
            val pair = libraryServers.getOrNull(p) ?: return@setOnItemClickListener
            val (entry, cds) = pair
            if (vm.indexSongCount(entry) > 0) {
                openServerPage(entry, cds) // 已建索引：直接进"专辑/歌手/歌曲"分类页
            } else {
                Toast.makeText(
                    this, "长按这台服务器可以建立索引，之后就能按专辑/歌手浏览", Toast.LENGTH_SHORT
                ).show()
                showMediaBrowser(cds) // 未建索引：沿用文件夹浏览
            }
        }
        listLibrary.setOnItemLongClickListener { _, _, p, _ ->
            val entry = libraryServers.getOrNull(p)?.first ?: return@setOnItemLongClickListener false
            showDeviceManageDialog(entry, entry.device)
            true
        }

        // 底部导航切换页面（同一 Tab 重复点击忽略）
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId != currentTabId) applyTab(item.itemId)
            true
        }
        refreshLibraryRows() // 提前准备好媒体服务器列表
        showPage(pageLibrary)
        vm.prepareRestoredQueue() // 恢复上次没播完的"待播列表"（按当前网络）
        vm.restoreCachedDevicesOnce() // 恢复设备快照：列表立即有内容，随后自动续扫刷新
        refreshLibraryRows()
        updateSettingsInfo()

        // 首次使用引导（只弹一次）
        val onboardPrefs = getSharedPreferences("ui_onboarding", MODE_PRIVATE)
        if (!onboardPrefs.getBoolean("shown_v1", false)) {
            mainHandler.post {
                AlertDialog.Builder(this)
                    .setTitle("三步开始使用")
                    .setMessage(
                        "1）点右上角「开始扫描」，发现局域网里的音箱 / 电视 / 媒体服务器\n" +
                            "2）在「媒体库」点开一台服务器，翻目录挑歌（也可以推手机里的本地文件）\n" +
                            "3）选一台设备推送播放；之后在「播放」页切歌、调音量、看队列\n\n" +
                            "提示：需要「附近的设备」权限用于局域网发现；本应用不会上传任何数据。"
                    )
                    .setCancelable(false)
                    .setPositiveButton("知道了") { _, _ ->
                        onboardPrefs.edit().putBoolean("shown_v1", true).apply()
                    }
                    .show()
            }
        }

        // ===== MVVM：UI 观察 ViewModel 状态（第 1+2 批） =====
        // 通用 UI 状态：扫描按钮/状态栏/正在播放控制条
        lifecycleScope.launch {
            vm.uiState.collect { s ->
                // 扫描开关（开始/停止互斥显示）
                btnStart.visibility = if (s.scanning) View.GONE else View.VISIBLE
                btnStop.visibility = if (s.scanning) View.VISIBLE else View.GONE
                if (s.statusOverride != null) {
                    tvStatus.text = s.statusOverride
                } else if (s.statusText != null) {
                    tvStatus.setText(s.statusText)
                }
                // 快捷入口：队列 / 最近播放（有记录才显示"最近"）
                btnQueueQuick.text = "队列(${s.queuePendingCount})"
                if (s.historyCount > 0) {
                    btnRecentQuick.visibility = View.VISIBLE
                    btnRecentQuick.text = "最近(${s.historyCount})"
                } else {
                    btnRecentQuick.visibility = View.GONE
                }
                // 正在播放：迷你条 + 播放页内容 同步渲染
                val meta = listOf(s.nowPlayingArtist, s.nowPlayingAlbum)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (s.nowPlayingActive) {
                    nowPlayingBar.visibility = View.VISIBLE
                    tvPlayEmpty.visibility = View.GONE
                    refreshMiniBar()

                    val playing = s.nowPlayingPlaying
                    btnNowPlayPause.setImageResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    btnMiniPlay.setImageResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )

                    tvNowDevice.text = "正在播放到：${s.nowPlayingDevice}"
                    tvNowTitle.text = s.nowPlayingTitle
                    miniTitle.text = s.nowPlayingTitle
                    miniMeta.text = if (meta.isNotEmpty()) meta else s.nowPlayingDevice

                    // 进度：不拖动时才跟随状态，避免与用户拖拽打架
                    if (!seekDragging) {
                        val dur = if (s.durationSec > 0) s.durationSec else 1
                        seekNow.max = dur.toInt()
                        seekNow.progress = s.positionSec.coerceIn(0, s.durationSec).toInt()
                    }
                    tvNowTime.text = fmtDuration(s.positionSec)
                    // 右侧显示"剩余时间"，如 -4:49（与主流音乐 App 一致）
                    tvNowDuration.text = if (s.durationSec > 0) {
                        val rem = (s.durationSec - s.positionSec).coerceAtLeast(0)
                        "-${fmtDuration(rem)}"
                    } else {
                        fmtDuration(s.durationSec)
                    }
                    seekNow.isEnabled = s.seekable && s.nowPlayingDevice.isNotEmpty()

                    // 音量：滑杆 + 数字（有 RenderingControl 才显示整行）
                    val hasRc = s.nowPlayingHasRc
                    volRow.visibility = if (hasRc) View.VISIBLE else View.GONE
                    if (hasRc) {
                        tvNowVolume.text = s.volume?.toString() ?: "--"
                        if (!volDragging && s.volume != null) seekVol.progress = s.volume ?: 0
                    }

                    // 队列 / 元数据 / 封面
                    tvNowQueue.text = "队列(${s.queuePendingCount})"
                    tvNowMeta.text = meta
                    tvNowMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
                    loadArtwork(s.nowPlayingArtUrl)
                } else {
                    nowPlayingBar.visibility = View.GONE
                    miniNowBar.visibility = View.GONE
                    tvPlayEmpty.visibility = View.VISIBLE
                    imgGlow.visibility = View.GONE
                }
            }
        }
        // 设备表变化 → 顺带刷新媒体库页的服务器列表
        lifecycleScope.launch {
            vm.deviceRows.collect {
                refreshLibraryRows()
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

    override fun onStart() {
        super.onStart()
        // 回到前台：之前是"扫描中"就自动续扫，设备列表/分类页数据不必重新点一遍
        vm.resumeAfterReturn()
        refreshLibraryRows()
        updateSettingsInfo()
        if (::searchAdapter.isInitialized && pageSearch.visibility == View.VISIBLE) {
            updateSearchStatus()
            runLocalSearch(etSearch.text.toString())
        }
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
                        "⚙ 设备管理（收藏 / 重命名）",
                        "ℹ 设备信息（IP/服务，调试）"
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
                        3 -> showDeviceInfoDialog(entry)
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
                menus += "ℹ 设备信息（IP/服务，调试）"
                actions += { showDeviceInfoDialog(entry) }
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

    /** 设备管理：收藏 / 重命名 / 恢复原名（服务器还能建立索引） */
    private fun showDeviceManageDialog(entry: Entry, device: UpnpDevice?) {
        val alias = vm.bookmarks.alias(vm.deviceIdOf(entry))
        val favorite = vm.isFavoriteEntry(entry)
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options += if (favorite) "取消收藏（从置顶移除 ⭐）" else "⭐ 收藏（列表置顶）"
        actions += { vm.toggleFavorite(entry.location) }
        options += if (alias == null) "重命名（设置别名）" else "重命名（当前别名：$alias）"
        actions += { showRenameDialog(entry, device, alias) }
        if (alias != null) {
            options += "恢复原名"
            actions += { vm.clearDeviceAlias(entry.location) }
        }
        val cds = device?.services?.firstOrNull { it.serviceType.contains("ContentDirectory") }
        if (cds != null) {
            options += "建立 / 更新索引（扫描曲库）"
            actions += { confirmIndex(entry, cds) }
        }

        AlertDialog.Builder(this)
            .setTitle(vm.shownNameOf(entry))
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
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

    /** 设备详细信息（IP/UDN/服务/URL 等调试信息） */
    private fun showDeviceInfoDialog(entry: Entry) {
        AlertDialog.Builder(this)
            .setTitle(vm.shownNameOf(entry))
            .setMessage(vm.deviceInfoText(entry))
            .setPositiveButton("好", null)
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

        fun fillLoading(text: String, retryAction: (() -> Unit)? = null) {
            labels.clear(); rowActions.clear()
            labels.add(if (retryAction != null) "$text\n（点这里重试）" else text)
            rowActions.add(retryAction ?: {})
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
                        fillLoading("Browse 失败：${out.error}") { reload() }
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
                    "下一首播放（插队，播完当前就播它）",
                    "加入队列（排到队尾）"
                )
            ) { _, which ->
                when (which) {
                    0 -> playMediaItemFromServer(item)
                    1 -> vm.queuePlayNext(item)
                    2 -> vm.queueEnqueue(item)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 曲库里点歌：弹出"选设备播放"框（上次用过的置顶、可一键；长按=收藏/重命名） */
    private fun playMediaItemFromServer(item: MediaItem) {
        if (item.resUrl.isBlank()) {
            Toast.makeText(this, "该条目没有可播放地址(res)", Toast.LENGTH_SHORT).show()
            return
        }

        val pairs = ArrayList<Pair<Entry, UpnpService>>()
        val names = ArrayList<String>()
        var listAdapter: ArrayAdapter<String>? = null
        var quickButton: Button? = null
        var dialog: AlertDialog? = null
        val lastKey = vm.lastRendererKey()

        fun labelOf(e: Entry): String {
            val d = e.device ?: return "（设备信息未就绪）"
            val star = if (vm.isFavoriteEntry(e)) "⭐ " else ""
            val last = if (lastKey != null && vm.rendererKeyOf(d, e.location) == lastKey) "  · 上次" else ""
            return "$star📺 ${vm.shownNameOf(e)}$last  ${d.modelName}"
        }

        fun collectRenderers() {
            pairs.clear()
            names.clear()
            val tmp = ArrayList<Pair<Entry, UpnpService>>()
            for (e in vm.deviceEntriesFavoritesFirst()) {
                val d = e.device ?: continue
                val avt = DlnaPlayer.avTransportOf(d) ?: continue
                tmp.add(e to avt)
            }
            // 排序：收藏最前 → 上次用过的其次 → 其余按发现顺序
            tmp.sortWith(
                compareBy(
                    { !vm.isFavoriteEntry(it.first) },
                    {
                        if (lastKey != null &&
                            vm.rendererKeyOf(it.first.device, it.first.location) == lastKey
                        ) 0 else 1
                    }
                )
            )
            pairs.addAll(tmp)
            for ((e, _) in pairs) names.add(labelOf(e))
        }

        fun lastIndex(): Int =
            pairs.indexOfFirst { (e, _) ->
                lastKey != null && vm.rendererKeyOf(e.device, e.location) == lastKey
            }

        fun refreshQuick() {
            val label = if (lastIndex() >= 0) "▶ 推给上次：${vm.shownNameOf(pairs[lastIndex()].first)}" else null
            quickButton?.let { btn ->
                btn.visibility = if (label != null) View.VISIBLE else View.GONE
                btn.text = label ?: ""
            }
        }

        fun pushSelected(index: Int) {
            val (e, avt) = pairs.getOrNull(index) ?: return
            val d = e.device ?: return
            pushToRenderer(
                renderer = avt,
                deviceName = d.friendlyName,
                rc = DlnaPlayer.renderingControlOf(d),
                item = item,
                device = d,
                deviceKey = e.location
            )
        }

        fun refresh() {
            vm.refreshDevicesNow()
            Toast.makeText(this, "正在搜索设备…", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({
                collectRenderers()
                listAdapter?.notifyDataSetChanged()
                refreshQuick()
                if (pairs.isEmpty()) Toast.makeText(this, "仍没找到可播放设备", Toast.LENGTH_SHORT).show()
            }, 1_500L)
        }

        collectRenderers()

        // 单台设备时：不弹框，直接推（原行为）
        if (pairs.size == 1) {
            pushSelected(0)
            return
        }

        val btnQuick = Button(this).apply {
            visibility = if (lastIndex() >= 0) View.VISIBLE else View.GONE
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand))
        }
        quickButton = btnQuick
        refreshQuick()

        val btnRefresh = Button(this).apply { text = "🔄 刷新设备列表" }
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.adapter = listAdapter

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 8, 30, 4)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                px(460)
            )
            addView(btnQuick)
            addView(listView)
            addView(btnRefresh)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("推送给哪台设备播放？")
            .setView(panel)
            .setNegativeButton("取消", null)
            .show()

        listView.setOnItemClickListener { _, _, which, _ ->
            dialog?.dismiss()
            pushSelected(which)
        }
        listView.setOnItemLongClickListener { _, _, which, _ ->
            val (e, _) = pairs.getOrNull(which) ?: return@setOnItemLongClickListener false
            showDeviceManageDialog(e, e.device)
            // 管理（收藏/重命名）后刷新标签与快速按钮
            mainHandler.postDelayed({
                collectRenderers()
                listAdapter?.notifyDataSetChanged()
                refreshQuick()
            }, 200)
            true
        }
        btnQuick.setOnClickListener {
            val idx = lastIndex()
            if (idx >= 0) {
                dialog?.dismiss()
                pushSelected(idx)
            } else {
                Toast.makeText(this, "上次的播放器已不在，请从下面选", Toast.LENGTH_SHORT).show()
            }
        }
        btnRefresh.setOnClickListener { refresh() }
    }

    /** 最近播放列表（第 7 课 D）：点一首 = 重播（走选设备流程） */
    private fun showHistoryDialog() {
        val list = vm.historyEntries()
        if (list.isEmpty()) {
            Toast.makeText(this, "还没有播放记录", Toast.LENGTH_SHORT).show()
            return
        }
        val rows = ArrayList<String>()
        for ((i, e) in list.withIndex()) {
            val suffix = listOf(e.artist, e.album).filter { it.isNotBlank() }
                .joinToString(" · ")
            rows += if (suffix.isNotEmpty()) "${i + 1}. ${e.title}（$suffix）" else "${i + 1}. ${e.title}"
        }
        AlertDialog.Builder(this)
            .setTitle("最近播放")
            .setItems(rows.toTypedArray()) { _, which ->
                list.getOrNull(which)?.let { replayHistoryEntry(it) }
            }
            .setNeutralButton("清空历史") { _, _ -> vm.historyClear() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 把一条历史记录还原成 MediaItem，走"选设备播放"重播 */
    private fun replayHistoryEntry(e: PlayHistory.Entry) {
        playMediaItemFromServer(
            MediaItem(
                id = e.resUrl,
                title = e.title,
                resUrl = e.resUrl,
                artist = e.artist,
                album = e.album,
                artUrl = e.artUrl
            )
        )
    }

    // ------------------------------------------------------------------
    // 第 7 课 B：本地文件推送
    // ------------------------------------------------------------------

    /** 选了本地文件：起手机端 HTTP 服务 -> 走"选设备播放"推给音箱/电视 */
    private fun pushLocalMedia(uri: Uri) {
        val name = queryLocalFileName(uri)
        Toast.makeText(this, "正在准备本地文件…", Toast.LENGTH_SHORT).show()
        vm.serveLocalFile(uri, name) { url, err ->
            if (url == null) {
                Toast.makeText(this, err ?: "本地文件推送失败", Toast.LENGTH_LONG).show()
                return@serveLocalFile
            }
            val title = name.substringBeforeLast('.').ifBlank { name }
            playMediaItemFromServer(
                MediaItem(id = url, title = title, resUrl = url)
            )
        }
    }

    /** 从 content:// 上查文件名（拿不到就退回路径最后一段） */
    private fun queryLocalFileName(uri: Uri): String {
        val fromQuery = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromQuery?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: "本地媒体"
    }

    /** 队列总览与管理：单击待播=立即切；长按待播=删除/上移/下移 */
    private fun showQueueDialog() {
        var queueDialog: AlertDialog? = null
        val rows = ArrayList<MediaItem>()
        val queueAdapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(p: Int): Any = rows[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val view = convert
                    ?: layoutInflater.inflate(R.layout.item_queue_row, parent, false)
                val item = rows[p]
                view.findViewById<TextView>(R.id.tvQIndex).text = (p + 1).toString()
                view.findViewById<TextView>(R.id.tvQTitle).text = item.title
                val sub = listOf(item.artist, item.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                view.findViewById<TextView>(R.id.tvQSub).text =
                    sub.ifEmpty { item.resUrl }
                return view
            }
        }

        val tvCurrent = TextView(this).apply {
            setPadding(4, 0, 4, 6)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val tvHint = TextView(this).apply {
            setPadding(4, 6, 4, 0)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_faint))
            text = "点待播一首 = 立即切到它；长按 = 管理（删除 / 上移 / 下移）"
        }
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            divider = null
            dividerHeight = 0
            adapter = queueAdapter
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 4)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(480)
            )
            addView(tvCurrent)
            addView(listView)
            addView(tvHint)
        }

        fun refresh() {
            rows.clear()
            rows.addAll(vm.queuePendingSnapshot())
            queueAdapter.notifyDataSetChanged()
            val cur = vm.playbackQueue.current
            tvCurrent.text = when {
                cur == null -> "（当前没有在播曲目）"
                nowSession.isActive -> "▶ 正在播：《${cur.title}》"
                else -> "▶ 当前：《${cur.title}》（还没开播/目标已不在）"
            }
        }
        refresh()

        listView.setOnItemClickListener { _, _, p, _ ->
            val target = rows.getOrNull(p) ?: return@setOnItemClickListener
            queueDialog?.dismiss()
            playQueueRow(target, p)
        }
        listView.setOnItemLongClickListener { _, _, p, _ ->
            val target = rows.getOrNull(p) ?: return@setOnItemLongClickListener false
            AlertDialog.Builder(this)
                .setTitle(target.title)
                .setItems(
                    arrayOf("立即播放", "上移", "下移", "删除")
                ) { _, which ->
                    when (which) {
                        0 -> {
                            queueDialog?.dismiss()
                            playQueueRow(target, p)
                        }
                        1 -> {
                            vm.queueMovePendingAt(p, -1)
                            refresh()
                        }
                        2 -> {
                            vm.queueMovePendingAt(p, 1)
                            refresh()
                        }
                        3 -> {
                            vm.queueRemovePendingAt(p)
                            refresh()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        queueDialog = AlertDialog.Builder(this)
            .setTitle("播放队列")
            .setView(panel)
            .setNeutralButton("清空队列") { _, _ ->
                vm.queueClear()
                rows.clear()
                queueAdapter.notifyDataSetChanged()
                tvCurrent.text = "（当前没有在播曲目）"
            }
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

    // ------------------------------------------------------------------
    // 三页切换 / 媒体库 / 音量（页面层工具）
    // ------------------------------------------------------------------

    /** 切 Tab（迷你条点击/下滑收起用） */
    private fun switchTab(itemId: Int) {
        if (bottomNav.selectedItemId != itemId) bottomNav.selectedItemId = itemId
    }

    /** 真正应用某个 Tab */
    private fun applyTab(itemId: Int) {
        val previous = currentTabId
        currentTabId = itemId
        if (itemId == R.id.nav_playing && previous != R.id.nav_playing) {
            lastTabBeforePlay = previous
        }
        when (itemId) {
            R.id.nav_playing -> showPage(pagePlay)
            R.id.nav_library -> {
                refreshLibraryRows()
                showPage(pageLibrary)
            }
            else -> showPage(pageSettings)
        }
        refreshMiniBar()
    }

    /** 显示指定页（三页互斥；搜索页/服务器页随切换收起） */
    private fun showPage(target: View) {
        pageSearch.visibility = View.GONE
        pageServer.visibility = View.GONE
        pagePlay.visibility = if (target === pagePlay) View.VISIBLE else View.GONE
        pageLibrary.visibility = if (target === pageLibrary) View.VISIBLE else View.GONE
        pageSettings.visibility = if (target === pageSettings) View.VISIBLE else View.GONE
    }

    /** 迷你条只在「有在播 且 不在播放页」时显示（播放页里它多余） */
    private fun refreshMiniBar() {
        val show = vm.uiState.value.nowPlayingActive && currentTabId != R.id.nav_playing
        miniNowBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 刷新媒体库页的服务器列表（MediaServer + ContentDirectory） */
    private fun refreshLibraryRows() {
        libraryServers.clear()
        for (e in vm.deviceEntriesFavoritesFirst()) {
            val d = e.device ?: continue
            val cds = d.services.firstOrNull { it.serviceType.contains("ContentDirectory") } ?: continue
            libraryServers.add(e to cds)
        }
        libraryServers.sortByDescending { vm.isFavoriteEntry(it.first) }
        if (::libraryAdapter.isInitialized) {
            libraryAdapter.notifyDataSetChanged()
            tvLibEmpty.visibility = if (libraryServers.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** 设置页：显示当前网络与数据作用域说明 */
    private fun updateSettingsInfo() {
        val net = NetworkScope.current()
        val text = buildString {
            append("当前网络：").append(NetworkScope.labelOf(net)).append('\n')
            append("· 设备列表 / 待播队列 / 最近播放：按网络分别保存（换网络各自独立）\n")
            append("· 收藏与别名：跟着设备走（同一台设备换网络也保留）\n")
            append("· 曲库索引：按服务器保存，搜索只显示当前在线服务器的结果")
        }
        if (tvSettingsInfo.text.toString() != text) tvSettingsInfo.text = text
    }

    /** 音量滑杆松手：把音量设到目标值（乐观更新 + 后台 SetVolume） */
    private fun sendVolume(target: Int) {
        val np = nowSession.current ?: return
        val rc = np.rc ?: return
        nowSession.syncVolume(target.coerceIn(0, 100))
        controlExecutor.execute {
            DlnaPlayer.setVolume(rc, target.coerceIn(0, 100))
        }
    }

    // ------------------------------------------------------------------
    // 服务器曲库：分类浏览（专辑 / 歌手 / 歌曲）
    // ------------------------------------------------------------------

    /** 打开某台服务器的分类浏览页（索引过的服务器走这里，不再默认弹文件夹） */
    private fun openServerPage(entry: Entry, cds: UpnpService) {
        serverEntry = entry
        serverCds = cds
        serverStage = 0          // 先进"顶层分类"页
        serverCategoryId = null
        serverCategoryTitle = "全部媒体"
        serverMode = 0
        serverFilter = null
        pageServer.visibility = View.VISIBLE
        pageLibrary.visibility = View.GONE
        pageSearch.visibility = View.GONE
        tvServerTitle.text = vm.shownNameOf(entry)
        updateServerTabs()
        refreshServerRows()
    }

    private fun closeServerPage() {
        pageServer.visibility = View.GONE
        serverEntry = null
        serverCds = null
        serverFilter = null
        showPage(
            when (currentTabId) {
                R.id.nav_playing -> pagePlay
                R.id.nav_settings -> pageSettings
                else -> pageLibrary
            }
        )
    }

    private fun switchServerMode(mode: Int) {
        serverMode = mode
        serverFilter = null
        updateServerTabs()
        refreshServerRows()
    }

    /** 分类 Tab 与"全部播放/入队"只在选中具体分类后才出现（分类总览页保持干净） */
    private fun updateServerTabs() {
        val insideCategory = serverStage >= 1
        val chromeVisibility = if (insideCategory) View.VISIBLE else View.GONE
        btnTabAlbums.visibility = chromeVisibility
        btnTabArtists.visibility = chromeVisibility
        btnTabSongs.visibility = chromeVisibility
        scopeRow.visibility = chromeVisibility

        fun style(btn: Button, active: Boolean) {
            btn.setBackgroundResource(if (active) R.drawable.bg_pill_primary else R.drawable.bg_pill_ghost)
            btn.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.on_brand else R.color.text_primary
                )
            )
        }
        style(btnTabAlbums, serverMode == 0)
        style(btnTabArtists, serverMode == 1)
        style(btnTabSongs, serverMode == 2)
    }

    /** 按当前层级/模式刷新列表 */
    private fun refreshServerRows() {
        val entry = serverEntry ?: return
        serverRows.clear()

        // ---- 第 0 层：顶层分类（音乐 / 视频 / 图片…）----
        if (serverStage == 0) {
            categoryTargets.clear()
            val cats = vm.indexCategories(entry)
            val total = cats.sumOf { it.itemCount }
            serverRows.add(ServerRow.Group("全部媒体", "$total 项 · 跨分类浏览"))
            categoryTargets.add(null)
            for (c in cats) {
                serverRows.add(ServerRow.Group(c.title, "${c.itemCount} 项 · ${c.kind}"))
                categoryTargets.add(c)
            }
            tvServerStatus.text =
                "共 ${cats.size} 个分类 · ${total} 项\n点一个分类进入（音乐/视频/图片由服务器目录决定）"
            serverAdapter.notifyDataSetChanged()
            tvServerEmpty.visibility = if (serverRows.isEmpty()) View.VISIBLE else View.GONE
            tvServerEmpty.text = "索引里没有分类信息\n可以点右上「文件夹」直接浏览，或重新建立索引"
            return
        }

        val topId = serverCategoryId
        val filter = serverFilter
        val prefix = "分类：$serverCategoryTitle · "

        when (serverMode) {
            0 -> { // 专辑
                if (filter == null) {
                    val albums = vm.indexAlbums(entry, topId)
                    albums.forEach { a ->
                        serverRows.add(
                            ServerRow.Group(
                                title = a.album,
                                sub = listOf(a.artist, "${a.songCount} 首")
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                            )
                        )
                    }
                    tvServerStatus.text =
                        prefix + "专辑 ${albums.size} 张 · 共 ${albums.sumOf { it.songCount }} 首"
                } else {
                    val songs = vm.indexSongsByAlbum(entry, filter, topId)
                    songs.forEach { serverRows.add(ServerRow.Song(it.toMediaItem())) }
                    tvServerStatus.text = "《$filter》 ${songs.size} 首"
                }
            }

            1 -> { // 歌手
                if (filter == null) {
                    val artists = vm.indexArtists(entry, topId)
                    artists.forEach { a ->
                        serverRows.add(
                            ServerRow.Group(
                                title = a.artist,
                                sub = "${a.songCount} 首 · ${a.albumCount} 张专辑"
                            )
                        )
                    }
                    tvServerStatus.text =
                        prefix + "歌手 ${artists.size} 位 · 共 ${artists.sumOf { it.songCount }} 首"
                } else {
                    val songs = vm.indexSongsByArtist(entry, filter, topId)
                    songs.forEach { serverRows.add(ServerRow.Song(it.toMediaItem())) }
                    tvServerStatus.text = "$filter ${songs.size} 首"
                }
            }

            else -> { // 歌曲
                val songs = vm.indexSongs(entry, topId)
                songs.forEach { serverRows.add(ServerRow.Song(it.toMediaItem())) }
                tvServerStatus.text = prefix + "曲目 ${songs.size} 项（点一项即可推送播放）"
            }
        }

        serverAdapter.notifyDataSetChanged()
        tvServerEmpty.visibility = if (serverRows.isEmpty()) View.VISIBLE else View.GONE
        tvServerEmpty.text = when {
            serverMode == 0 && filter == null -> "这个分类里没有专辑信息\n（有些文件没写专辑标签，可以看「歌曲」页）"
            serverMode == 1 && filter == null -> "这个分类里没有歌手信息\n（有些文件没写歌手标签，可以看「歌曲」页）"
            else -> "这个分类下没有内容"
        }

        // "全部播放 / 全部入队"作用范围 = 当前所见范围（某专辑 / 某歌手 / 当前分类全部）
        val scopeCount = serverPlayScopeSongs().size
        btnPlayAll.text = "▶ 全部播放（$scopeCount）"
        btnQueueAll.text = "＋ 全部入队（$scopeCount）"
        btnPlayAll.isEnabled = scopeCount > 0
        btnQueueAll.isEnabled = scopeCount > 0
    }

    /**
     * 当前"全部播放/全部入队"的作用范围：
     *   - 第 0 层 / 分类内总览 / 歌曲页 → 当前分类（或全部）的所有曲目
     *   - 进了某专辑 / 某歌手 → 那一批
     */
    private fun serverPlayScopeSongs(): List<MediaIndexStore.IndexEntry> {
        val entry = serverEntry ?: return emptyList()
        val filter = serverFilter
        val topId = serverCategoryId
        return when {
            serverMode == 0 && filter != null -> vm.indexSongsByAlbum(entry, filter, topId)
            serverMode == 1 && filter != null -> vm.indexSongsByArtist(entry, filter, topId)
            else -> vm.indexSongs(entry, topId)
        }
    }

    private fun serverPlayScopeLabel(): String {
        val filter = serverFilter
        return when {
            serverMode == 0 && filter != null -> "《${filter}》"
            serverMode == 1 && filter != null -> filter
            else -> serverCategoryTitle
        }
    }

    /** 全部播放：第一首推给设备，其余进入待播列表按顺序自动连播 */
    private fun playAllSongs(songs: List<MediaIndexStore.IndexEntry>, label: String) {
        val items = songs.map { it.toMediaItem() }
        if (items.isEmpty()) return
        vm.replaceQueueWith(items.drop(1), label, items.size)
        playMediaItemFromServer(items.first())
    }

    /** 专辑/歌手分组长按：全部播放 / 全部加入队列 / 只播放第一首 */
    private fun showGroupMenu(groupTitle: String) {
        val entry = serverEntry ?: return
        val topId = serverCategoryId
        val songs = if (serverMode == 0) vm.indexSongsByAlbum(entry, groupTitle, topId)
        else vm.indexSongsByArtist(entry, groupTitle, topId)
        if (songs.isEmpty()) {
            Toast.makeText(this, "这个分组下没有曲目", Toast.LENGTH_SHORT).show()
            return
        }
        val label = if (serverMode == 0) "《$groupTitle》" else groupTitle
        AlertDialog.Builder(this)
            .setTitle("$label（${songs.size} 首）")
            .setItems(arrayOf("▶ 全部播放（从这里开始）", "＋ 全部加入队列", "只播放第一首")) { _, which ->
                when (which) {
                    0 -> playAllSongs(songs, label)
                    1 -> vm.queueEnqueueAll(songs.map { it.toMediaItem() }, label)
                    2 -> playMediaItemFromServer(songs.first().toMediaItem())
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 第 8 课：搜索与索引（页面前端逻辑）
    // ------------------------------------------------------------------

    /** 显示/隐藏搜索页（不是底部 Tab，从媒体库进入） */
    private fun showSearchPage(show: Boolean) {
        pageSearch.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            pageLibrary.visibility = View.GONE
            updateSearchStatus()
            runLocalSearch(etSearch.text.toString())
        } else {
            showPage(
                when (currentTabId) {
                    R.id.nav_playing -> pagePlay
                    R.id.nav_settings -> pageSettings
                    else -> pageLibrary
                }
            )
        }
    }

    private fun updateSearchStatus() {
        val st = vm.indexStats()
        val indexing = if (vm.isIndexing()) " · 正在建立索引…" else ""
        tvSearchStatus.text =
            "本地索引：${st.itemCount} 首 · 待扫目录 ${st.pendingContainers} 个$indexing\n" +
                "提示：先在「媒体库」长按服务器建立索引，之后搜索就是秒出"
    }

    /** 本地索引搜索（输入即搜） */
    private fun runLocalSearch(raw: String) {
        val kw = raw.trim()
        searchItems.clear()
        if (kw.isEmpty()) {
            searchAdapter.notifyDataSetChanged()
            tvSearchEmpty.visibility = View.VISIBLE
            tvSearchEmpty.text = "输入关键词开始搜索\n（歌名 / 歌手 / 专辑）"
            updateSearchStatus()
            return
        }
        val hits = vm.searchIndex(kw)
        // 第 9 课：只显示"当前网络在线服务器"的条目，避免搜索出换网络后播不了的歌
        val present = vm.presentServerKeys()
        val usable = hits.filter { present.isEmpty() || it.serverUdn in present }
        val hidden = hits.size - usable.size
        searchItems.addAll(usable.map { it.toMediaItem() })
        searchAdapter.notifyDataSetChanged()
        tvSearchEmpty.visibility = if (searchItems.isEmpty()) View.VISIBLE else View.GONE
        tvSearchEmpty.text = "本地索引里没有匹配「$kw」\n可以点下方「在服务器上搜（实验）」"
        tvSearchStatus.text = buildString {
            append("本地索引命中 ${searchItems.size} 条（索引共 ${vm.indexStats().itemCount} 首）")
            if (hidden > 0) append("\n已隐藏 $hidden 条：来自不在当前网络的服务器")
        }
    }

    /** 服务端搜索（先查 SCPD 是否支持 Search） */
    private fun serverSearch(entry: Entry, cds: UpnpService, keyword: String) {
        tvSearchStatus.text = "正在 ${vm.shownNameOf(entry)} 上搜索「$keyword」…"
        vm.searchOnServer(entry, cds, keyword) { results, error ->
            if (error != null) {
                tvSearchStatus.text = error
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                return@searchOnServer
            }
            val items = results.filterIsInstance<MediaItem>().filter { it.resUrl.isNotBlank() }
            searchItems.clear()
            searchItems.addAll(items)
            searchAdapter.notifyDataSetChanged()
            tvSearchEmpty.visibility = if (searchItems.isEmpty()) View.VISIBLE else View.GONE
            tvSearchEmpty.text = "服务器上没有匹配「$keyword」的结果"
            tvSearchStatus.text = "服务器搜索命中 ${searchItems.size} 条（${vm.shownNameOf(entry)}）"
        }
    }

    /** 搜索结果项的长按菜单 */
    private fun showSearchItemMenu(item: MediaItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("立即播放", "下一首播放（插队）", "加入队列")) { _, which ->
                when (which) {
                    0 -> playMediaItemFromServer(item)
                    1 -> vm.queuePlayNext(item)
                    2 -> vm.queueEnqueue(item)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选择一台媒体服务器（0/1/多台三种情况） */
    private fun pickServerFor(title: String, onPick: (Entry, UpnpService) -> Unit) {
        refreshLibraryRows()
        when {
            libraryServers.isEmpty() -> Toast.makeText(
                this, "还没发现媒体服务器：先回「媒体库」点开始扫描", Toast.LENGTH_LONG
            ).show()

            libraryServers.size == 1 -> {
                val (e, cds) = libraryServers[0]
                onPick(e, cds)
            }

            else -> AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(libraryServers.map { vm.shownNameOf(it.first) }.toTypedArray()) { _, w ->
                    libraryServers.getOrNull(w)?.let { onPick(it.first, it.second) }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 建立索引前确认：继续扫 / 从头更新 */
    private fun confirmIndex(entry: Entry, cds: UpnpService) {
        val st = vm.indexStats()
        AlertDialog.Builder(this)
            .setTitle("建立索引：${vm.shownNameOf(entry)}")
            .setMessage(
                "把服务器曲库扫一遍，之后搜索就是秒出。\n" +
                    "当前索引：${st.itemCount} 首，待扫目录 ${st.pendingContainers} 个。\n\n" +
                    "· 继续扫描：接着没扫完的目录继续\n" +
                    "· 更新索引：清掉扫描状态，从头重新扫一遍"
            )
            .setPositiveButton("继续扫描") { _, _ -> startIndex(entry, cds, reset = false) }
            .setNeutralButton("更新索引") { _, _ -> startIndex(entry, cds, reset = true) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startIndex(entry: Entry, cds: UpnpService, reset: Boolean) {
        if (vm.isIndexing()) {
            Toast.makeText(this, "索引正在建立中，可先停止", Toast.LENGTH_SHORT).show()
            return
        }
        if (reset) vm.resetServerIndex(entry)
        indexDialog?.dismiss()
        indexDialog = AlertDialog.Builder(this)
            .setTitle("建立索引：${vm.shownNameOf(entry)}")
            .setMessage("准备中…")
            .setNegativeButton("停止") { _, _ -> vm.stopIndexing() }
            .setCancelable(false)
            .show()
        vm.startIndexing(entry, cds)
    }

    // ------------------------------------------------------------------
    // 第 7 课 C：专辑封面下载（后台线程；失败或切歌时安全隐藏）
    // ------------------------------------------------------------------

    /** 封面加载：同一张封面喂给 播放页大封面 / 迷你条 / 背景氛围；切歌防串图 */
    private fun loadArtwork(url: String) {
        if (url.isBlank()) {
            lastArtUrl = null
            imgNowArt.visibility = View.GONE
            miniArt.visibility = View.GONE
            imgGlow.visibility = View.GONE
            return
        }
        if (url == lastArtUrl) return // 已处理过（成功/失败都记，避免反复请求）
        lastArtUrl = url
        imgNowArt.visibility = View.GONE // 先隐藏，加载成功再亮
        miniArt.visibility = View.GONE
        imgGlow.visibility = View.GONE
        fetchExecutor.execute {
            val bytes = runCatching { downloadWithLimit(url, MAX_ART_BYTES) }.getOrNull()
            val bitmap = bytes?.let { decodeScaled(it, ART_TARGET_PX) }
            mainHandler.post {
                if (vm.uiState.value.nowPlayingArtUrl == url) {
                    if (bitmap != null) {
                        imgNowArt.setImageBitmap(bitmap)
                        imgNowArt.visibility = View.VISIBLE
                        miniArt.setImageBitmap(bitmap)
                        miniArt.visibility = View.VISIBLE
                        imgGlow.setImageBitmap(bitmap)
                        imgGlow.visibility = View.VISIBLE
                    } else {
                        imgNowArt.visibility = View.GONE
                        miniArt.visibility = View.GONE
                        imgGlow.visibility = View.GONE
                    }
                }
            }
        }
    }

    /** 下载图片字节（限长，防超大图拖垮内存） */
    private fun downloadWithLimit(url: String, maxBytes: Int): ByteArray? {
        val conn = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 缩放解码：最长边超过 target 就按比例抽稀，避免解码超大原图 */
    private fun decodeScaled(data: ByteArray, target: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
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
                    setNowPlaying(
                        deviceName = deviceName,
                        avt = renderer,
                        rc = rc,
                        title = item.title,
                        deviceKey = deviceKey,
                        artist = item.artist,
                        album = item.album,
                        artUrl = item.artUrl
                    )
                    // 记入播放队列的"当前这首"（排队的歌会在播完后自动接上）
                    vm.queueOnPlayed(item)
                    // 第 7 课 D：曲库推送成功 -> 记入"最近播放"
                    vm.noteHistoryPlayed(item)
                    // 记住"上次推给哪台设备"（下次默认）
                    vm.rememberLastRenderer(vm.rendererKeyOf(device, deviceKey))
                    Toast.makeText(this, "已推送给 $targetName 播放", Toast.LENGTH_SHORT).show()
                    // 操作了这台设备 -> 自动订阅其全部服务（之后别处操作也能同步）
                    vm.activateDeviceSubscription(deviceKey, device.services)
                }
            } else {
                // 失败要能救：给"重试 / 换一台"的出口，而不是只弹个 toast
                val reason = results.lastOrNull()?.second?.summary() ?: "未知错误"
                mainHandler.post {
                    showPushFailedDialog(item, renderer, rc, deviceName, device, deviceKey, reason)
                }
            }
        }
    }

    /** 推送失败：重试同一台 / 换一台设备 / 取消 */
    private fun showPushFailedDialog(
        item: MediaItem,
        renderer: UpnpService,
        rc: UpnpService?,
        deviceName: String,
        device: UpnpDevice,
        deviceKey: String,
        reason: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("推送失败")
            .setMessage("《${item.title}》推给 ${deviceName.ifEmpty { "播放器" }} 失败：\n$reason")
            .setPositiveButton("重试") { _, _ ->
                pushToRenderer(renderer, deviceName, rc, item, device, deviceKey)
            }
            .setNeutralButton("换一台") { _, _ -> playMediaItemFromServer(item) }
            .setNegativeButton("取消", null)
            .show()
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
        deviceKey: String? = null,
        artist: String = "",
        album: String = "",
        artUrl: String = ""
    ) {
        nowSession.begin(
            deviceName, avt, rc, title,
            deviceKey = deviceKey, artist = artist, album = album, artUrl = artUrl
        )
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
                        val pushedTitle = url.substringAfterLast('/').ifEmpty { url }
                        setNowPlaying(
                            deviceName = deviceName,
                            avt = renderer,
                            rc = rc,
                            title = pushedTitle,
                            deviceKey = deviceKey
                        )
                        // 第 7 课 D：裸 URL 播放也算进"最近播放"
                        vm.noteHistoryPlayed(
                            MediaItem(id = url, title = pushedTitle, resUrl = url)
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

        /** 封面下载上限（字节）：防止个别超大图拖垮内存 */
        private const val MAX_ART_BYTES = 2 * 1024 * 1024

        /** 封面显示目标边长（px，物理像素） */
        private const val ART_TARGET_PX = 240

        /** logcat 统一 TAG：adb logcat -s MyUPNP 过滤 */
        private const val TAG = "MyUPNP"
    }
}
