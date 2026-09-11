package com.example.myupnp

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myupnp.core.EverythingFreeGate
import com.example.myupnp.core.FeatureGate
import com.example.myupnp.core.FeatureId
import com.example.myupnp.device.ScpdLoader
import com.example.myupnp.dlna.ContentDirectoryClient
import com.example.myupnp.dlna.DlnaPlayer
import com.example.myupnp.dlna.LastChangeParser
import com.example.myupnp.gena.EventProperties
import com.example.myupnp.gena.GenaClient
import com.example.myupnp.gena.LocalEventServer
import com.example.myupnp.gena.LocalIp
import com.example.myupnp.model.MediaItem
import com.example.myupnp.model.MediaObject
import com.example.myupnp.model.UpnpDevice
import com.example.myupnp.model.UpnpService
import com.example.myupnp.ssdp.SsdpDiscovery
import com.example.myupnp.ssdp.SsdpMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * MainViewModel —— MVVM 迁移（第 3 批）
 * ------------------------------------------------------------------
 * 本批把"扫描子系统"完整收编：SSDP 引擎、事件回调服务器、组播锁、
 * 心跳清理（协程）、Wi-Fi 网络感知决策 —— 全部在这里管理。
 * MainActivity 只负责：注册/注销系统网络回调（生命周期绑定）、
 * 把网络事件转进来、渲染状态、弹对话框。
 *
 * 收费口子：[featureGate] 集中注入，业务层统一走它。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    val mainHandler: Handler = Handler(Looper.getMainLooper())
    val fetchExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "description-fetch").apply { isDaemon = true }
    }
    val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soap-control").apply { isDaemon = true }
    }

    /** 兜底轮询专用线程：不占用 controlExecutor，避免卡住用户的操作 */
    private val pollExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "upnp-poll").apply { isDaemon = true }
    }
    val featureGate: FeatureGate = EverythingFreeGate

    /** 设备级用户数据（第 7 课 E：收藏/别名，持久化） */
    val bookmarks = DeviceBookmarks(getApplication())

    /** 播放历史（第 7 课 D：最近播放，持久化） */
    val playHistory = PlayHistory(getApplication())

    /** 上次推送选中的播放器（记住默认，下次优先） */
    private val rendererPrefs =
        getApplication<Application>().getSharedPreferences("renderer_memory", Context.MODE_PRIVATE)

    /** 播放器稳定键：UDN 优先，回退 LOCATION（用于记住"上次用哪台"） */
    fun rendererKeyOf(device: UpnpDevice?, location: String?): String =
        device?.udn?.trim()?.takeIf { it.isNotBlank() } ?: location.orEmpty()

    fun lastRendererKey(): String? = rendererPrefs.getString("last", null)

    fun rememberLastRenderer(key: String) {
        if (key.isBlank()) return
        rendererPrefs.edit().putString("last", key).apply()
    }

    // ------------------------------------------------------------------
    // 第 8 课：本地索引 + 搜索
    // ------------------------------------------------------------------

    /** 本地媒体索引（SQLite） */
    val indexStore = MediaIndexStore(getApplication())

    /** 曲库索引器（后台 BFS 扫描，可停止/续扫） */
    val indexer = LibraryIndexer(
        store = indexStore,
        listener = object : LibraryIndexer.Listener {
            override fun onProgress(scannedContainers: Int, indexedItems: Int, currentPath: String) {
                mainHandler.post { indexProgressListener?.invoke(scannedContainers, indexedItems, currentPath) }
            }

            override fun onFinished(serverUdn: String, indexedItems: Int, reason: String) {
                mainHandler.post { indexDoneListener?.invoke(serverUdn, indexedItems, reason) }
            }
        }
    )

    /** 索引进度 / 结束回调（Activity 注册，可为空） */
    @Volatile
    var indexProgressListener: ((scanned: Int, items: Int, path: String) -> Unit)? = null

    @Volatile
    var indexDoneListener: ((serverUdn: String, items: Int, reason: String) -> Unit)? = null

    /** 开始（或继续）索引一台服务器 */
    fun startIndexing(entry: Entry, cds: UpnpService) {
        if (indexer.isRunning()) {
            postMessage("索引正在建立中，请稍候（可停止）")
            return
        }
        Log.i(TAG, "[INDEX] 开始索引: ${shownNameOf(entry)}")
        postMessage("开始建立索引：${shownNameOf(entry)}")
        indexer.start(deviceIdOf(entry), cds)
    }

    fun stopIndexing() = indexer.stop()

    fun isIndexing(): Boolean = indexer.isRunning()

    fun indexStats(): MediaIndexStore.Stats = indexStore.stats()

    /** 本地索引搜索（同步，几毫秒级；调用方在主线程即可） */
    fun searchIndex(keyword: String): List<MediaIndexStore.IndexEntry> = indexStore.search(keyword)

    // ---- 分类浏览（专辑 / 歌手 / 歌曲）：索引过的服务器走这套 ----

    /** 这台服务器索引里有多少首（>0 表示"已建索引"） */
    fun indexSongCount(entry: Entry): Int = indexStore.countForServer(deviceIdOf(entry))

    fun indexAlbums(entry: Entry) = indexStore.albums(deviceIdOf(entry))

    fun indexArtists(entry: Entry) = indexStore.artists(deviceIdOf(entry))

    fun indexSongs(entry: Entry) = indexStore.songs(deviceIdOf(entry))

    fun indexSongsByAlbum(entry: Entry, album: String) =
        indexStore.songsByAlbum(deviceIdOf(entry), album)

    fun indexSongsByArtist(entry: Entry, artist: String) =
        indexStore.songsByArtist(deviceIdOf(entry), artist)

    /** 批量入队（整张专辑 / 某歌手全部）；只弹一条提示，不刷屏 */
    fun queueEnqueueAll(items: List<MediaItem>, label: String) {
        if (items.isEmpty()) return
        for (item in items) playbackQueue.enqueue(item)
        syncQueueUi()
        postMessage("已把 $label 的 ${items.size} 首加入队列（当前这首播完接着播）")
    }

    /** 清掉某台服务器的目录扫描状态（= 下次 startIndexing 会重新扫） */
    fun resetServerIndex(entry: Entry) = indexStore.resetServer(deviceIdOf(entry))

    fun clearIndex() = indexStore.clearAll()

    /**
     * 服务端搜索：先读 SCPD 判断有没有 Search 动作（不少设备不支持），
     * 支持就先按 contains 语法搜，失败再用 like 语法重试一次。
     */
    fun searchOnServer(
        entry: Entry,
        cds: UpnpService,
        keyword: String,
        onDone: (results: List<MediaObject>, error: String?) -> Unit
    ) {
        controlExecutor.execute {
            val actions = runCatching { ScpdLoader.load(cds.scpdUrl) }.getOrDefault(emptyList())
            val supports = actions.any { it.name.equals("Search", ignoreCase = true) }
            if (!supports) {
                mainHandler.post {
                    onDone(emptyList(), "这台服务器没有声明 Search 动作（可先用本地索引搜索）")
                }
                return@execute
            }
            var out = ContentDirectoryClient.search(cds, keyword, useLike = false)
            if (!out.ok) out = ContentDirectoryClient.search(cds, keyword, useLike = true)
            mainHandler.post {
                if (out.ok) onDone(out.objects, null)
                else onDone(emptyList(), "服务器搜索失败：${out.error}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 设备/订阅/播放状态对象
    // ------------------------------------------------------------------

    val registry = DeviceRegistry(
        mainHandler = mainHandler,
        fetchExecutor = fetchExecutor,
        listener = object : DeviceRegistry.Listener {
            override fun onRegistryChanged() {
                rebuildDeviceRows()
            }
        }
    )

    val nowSession = NowPlayingSession(
        mainHandler = mainHandler,
        controlExecutor = controlExecutor,
        listener = object : NowPlayingSession.Listener {
            override fun onChanged(session: NowPlayingSession) {
                syncNowPlayingState()
            }

            /** 一首自然播完（不是手动停止）-> 自动连播排队的第一首 */
            override fun onTrackEnded(session: NowPlayingSession) {
                autoAdvanceQueue()
            }
        }
    )

    /** 播放队列（第 7 课 B）：排队 + 播完自动连播 */
    val playbackQueue = PlaybackQueue()

    /** 队列持久化（第 7 课 A：重启恢复"待播列表"） */
    private val queuePrefs =
        getApplication<Application>().getSharedPreferences("play_queue", Context.MODE_PRIVATE)
    private var queueRestored = false

    val subManager = SubscriptionManager(
        mainHandler = mainHandler,
        controlExecutor = controlExecutor,
        listener = object : SubscriptionManager.Listener {
            override fun onInfo(message: String) = postMessage(message)
            override fun onError(message: String) = postMessage(message, isError = true)
        }
    )

    /**
     * 活动设备自动订阅：播放/操作某台设备成功后调用。
     * 自动订阅该设备"全部服务"，此后其它 Client 对该设备的操作
     * （进度/音量等）都会经 GENA 推送同步到我们。
     * 切换到另一台设备时，自动退订旧设备的订阅再订新的。
     *
     * @param key       设备唯一键（用 Entry.location / UDN 均可，用于判断"换设备了"）
     * @param services  该设备描述里的全部服务
     */
    private var activeDeviceKey: String? = null

    fun activateDeviceSubscription(key: String, services: List<UpnpService>) {
        if (key == activeDeviceKey) return // 同一台设备，别重复折腾
        deactivateDeviceSubscription()     // 切设备：先退旧的（所有）
        activeDeviceKey = key
        val cb = eventCallbackUrl ?: return
        Log.i(TAG, "[SUB] 活动设备自动订阅: $key（${services.size} 个服务）")
        for (svc in services) {
            if (svc.eventSubUrl.isNotBlank() && !subManager.isSubscribed(svc.eventSubUrl)) {
                subManager.subscribe(svc, cb)
            }
        }
    }

    /** 结束活动设备订阅（会话结束/退出时调用） */
    fun deactivateDeviceSubscription() {
        if (activeDeviceKey != null) {
            Log.i(TAG, "[SUB] 退订活动设备: $activeDeviceKey")
        }
        activeDeviceKey = null
        subManager.unsubscribeAll()
    }

    // ------------------------------------------------------------------
    // 播放进度 ticker：常驻轻量循环，播放中每秒推进一次本地进度
    // ------------------------------------------------------------------

    init {
        viewModelScope.launch {
            while (isActive) {
                nowSession.tick(1_000L)   // 仅在 playing 时真正推进并回调
                delay(1_000L)
            }
        }
        // 兜底轮询：只在"很久没收到 GENA 事件"时发查询，事件正常时几乎零开销
        viewModelScope.launch {
            var step = 0
            while (isActive) {
                delay(POLL_TICK_MS)
                step++
                pollSessionFallback(step)
            }
        }
    }

    /**
     * 事件不可靠时的兜底：定时用 GetTransportInfo/GetPositionInfo/GetVolume 校准
     * 播放状态、进度、音量，避免进度条/音量"漂着不准"。
     * 判定依据：距上次收到事件超过 POLL_EVENT_FRESH_MS 才轮询。
     */
    private fun pollSessionFallback(step: Int) {
        val np = nowSession.current ?: return
        if (nowSession.millisSinceLastEvent() < POLL_EVENT_FRESH_MS) return // 事件正常，不打扰
        pollExecutor.execute {
            val state = runCatching { DlnaPlayer.getTransportState(np.avt) }.getOrNull()
            if (state != null) mainHandler.post { nowSession.applyPolledTransportState(state) }

            if (step % 3 == 0) { // 约每 15 秒校准一次进度
                val info = runCatching { DlnaPlayer.getPositionInfo(np.avt) }.getOrNull()
                if (info != null) {
                    mainHandler.post {
                        nowSession.syncProgress(info.positionSec, info.durationSec)
                    }
                }
            }
            if (step % 6 == 0) { // 约每 30 秒校准一次音量
                val rc = np.rc
                if (rc != null) {
                    val v = runCatching { DlnaPlayer.getVolume(rc) }.getOrDefault(-1)
                    if (v >= 0) mainHandler.post { nowSession.syncVolume(v) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 扫描子系统（第 3 批：从 Activity 收编到这里）
    // ------------------------------------------------------------------

    /** SSDP 发现引擎 */
    val discovery = SsdpDiscovery(object : SsdpDiscovery.Listener {
        override fun onSsdpMessage(message: SsdpMessage) {
            mainHandler.post { handleSsdpMessage(message) }
        }

        override fun onEngineError(error: Throwable) {
            Log.w(TAG, "[SSDP!] 引擎错误: ${error.message}")
        }

        override fun onSsdpInfo(info: String) {
            Log.d(TAG, "[SSDP] $info")
        }
    })

    /** App 内嵌的事件回调服务器（收设备 NOTIFY 推送） */
    private val eventServer = LocalEventServer(object : LocalEventServer.Listener {
        override fun onEvent(remote: String, sid: String?, nts: String?, body: String) {
            mainHandler.post { handleIncomingEvent(remote, sid, nts, body) }
        }

        override fun onInfo(info: String) {
            Log.d(TAG, "[EVENT-SERVER] $info")
        }
    })

    /** 本地文件 HTTP 服务（第 7 课 B：把手机里的媒体推给音箱/电视） */
    val fileServer = LocalFileServer(getApplication())

    private var multicastLock: WifiManager.MulticastLock? = null

    // ---- A2：Wi-Fi 网络感知状态 ----
    /** 用户是否想要扫描（点过开始；手动停止才置 false） */
    private var userWantsScan = false
    private var wifiConnected = false
    private var lastKnownWifiIp: String? = null
    private var startupPending = false

    /** 心跳协程 Job（扫描期间运行） */
    private var heartbeatJob: Job? = null

    // ------------------------------------------------------------------
    // 扫描启停（UI 按钮 / A2 自动续扫都调这里）
    // ------------------------------------------------------------------

    /** 开始扫描：拿组播锁、起引擎、起事件服务器、启心跳协程 */
    fun startScan() {
        userWantsScan = true
        Log.i(TAG, "[SCAN] 开始扫描，请求 MulticastLock")
        multicastLock = try {
            val wifi = getApplication<Application>()
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("myupnp-scan").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[SCAN] MulticastLock 获取失败: ${e.message}")
            null
        }

        discovery.start()

        if (eventServer.port == 0) {
            eventServer.start()
            Log.i(TAG, "[SCAN] 事件回调服务器端口=${eventServer.port} 本机回调=$eventCallbackUrl")
        }

        setStatusOverride(null) // 清掉"Wi-Fi 断开"这类临时文案
        _uiState.update { it.copy(scanning = true, statusText = R.string.status_scanning) }
        startHeartbeat()
        lastKnownWifiIp = LocalIp.ipv4()
    }

    /** 手动停止扫描：以后网络恢复也不自动续扫 */
    fun stopScan() {
        userWantsScan = false
        Log.i(TAG, "[SCAN] 停止扫描")
        stopHeartbeat()
        discovery.stop()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        subManager.unsubscribeAll()
        eventServer.stop()
        _uiState.update { it.copy(scanning = false, statusText = R.string.status_idle) }
    }

    /** 清空设备列表（顶部"清空设备"按钮） */
    fun clearAllDevices() {
        deactivateDeviceSubscription()
        registry.clearAll()
        clearNowPlayingIfDeviceGone()
    }

    /** 立即主动搜索一次（用户点"刷新设备"时用） */
    fun refreshDevicesNow() {
        discovery.forceSearch()
    }

    /**
     * 释放扫描资源（引擎/锁/订阅/回调服务器/列表/心跳），
     * 不改 userWantsScan —— 供"Wi-Fi 断开/IP 变化"这类暂时停使用。
     */
    private fun releaseScanResources(reason: String) {
        Log.i(TAG, "[SCAN] 释放资源: $reason")
        stopHeartbeat()
        discovery.stop()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        subManager.unsubscribeAll()
        eventServer.stop()
        registry.clearAll()
        clearNowPlayingIfDeviceGone()
    }

    // ------------------------------------------------------------------
    // A2：Wi-Fi 变化（由 Activity 的网络回调转发进来，主线程调用）
    // ------------------------------------------------------------------

    /** Wi-Fi 连上（可能还没 IP），先记状态 */
    fun onWifiAvailable() {
        Log.i(TAG, "[NET] Wi-Fi 连接")
        wifiConnected = true
    }

    /** Wi-Fi 断开：若用户要扫，释放资源等重连 */
    fun onWifiLost() {
        Log.w(TAG, "[NET] Wi-Fi 断开")
        wifiConnected = false
        if (!userWantsScan || !discovery.isRunning()) return
        releaseScanResources("Wi-Fi 断开")
        setStatusOverride("Wi-Fi 断开，等待重连后自动续扫…")
    }

    /** Wi-Fi 拿到 IP：恢复启动 / 同网段换 IP 重建 */
    fun onWifiIpReady(ip: String) {
        if (!userWantsScan) return
        if (!discovery.isRunning()) {
            // 场景 A：想扫但引擎没跑（刚重连）-> 稍等再启动
            if (wifiConnected && !startupPending) {
                startupPending = true
                Log.i(TAG, "[NET] Wi-Fi 就绪(IP=$ip)，延迟后启动扫描")
                viewModelScope.launch {
                    delay(NET_RESTART_DELAY_MS)
                    startupPending = false
                    if (userWantsScan && wifiConnected && !discovery.isRunning()) {
                        Log.i(TAG, "[NET] 自动重启扫描")
                        startScan()
                    }
                }
            }
        } else {
            // 场景 B：引擎在跑但 IP 变了（DHCP 续租 / 换 AP）
            if (ip != lastKnownWifiIp) {
                Log.w(TAG, "[NET] IP 变化 $lastKnownWifiIp -> $ip，重建订阅与扫描")
                releaseScanResources("IP 变化")
                if (!startupPending) {
                    startupPending = true
                    viewModelScope.launch {
                        delay(NET_RESTART_DELAY_MS)
                        startupPending = false
                        if (userWantsScan && wifiConnected) startScan()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 心跳清理（协程版）
    // ------------------------------------------------------------------

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                pruneStaleDevices()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 移除超时设备，并顺带清理残留 GENA 订阅 */
    private fun pruneStaleDevices() {
        val removed = registry.pruneStale(DEVICE_STALE_MS)
        if (removed.isEmpty()) return
        for (entry in removed) {
            entry.device?.services?.forEach { svc ->
                subManager.unsubscribeByUrl(svc.eventSubUrl)
            }
        }
        clearNowPlayingIfDeviceGone()
    }

    // ------------------------------------------------------------------
    // SSDP / 事件消息处理
    // ------------------------------------------------------------------

    private fun handleSsdpMessage(message: SsdpMessage) {
        Log.d(
            TAG,
            "[SSDP:${message.type}] from=${message.sourceHost} " +
                "usn=${message.usn ?: "-"} loc=${message.location ?: "-"} " +
                "nt=${message.nt ?: "-"}"
        )
        registry.onSsdpMessage(message)
    }

    private fun handleIncomingEvent(remote: String, sid: String?, nts: String?, body: String) {
        Log.d(TAG, "[EVENT] from=$remote sid=$sid nts=$nts body=${body.take(400)}")
        val props = EventProperties.parse(body)
        // 通过 SID 反查是哪个服务的订阅（喂给控制条纠正状态）
        val eventServiceUrl = subManager.eventUrlBySid(sid)
        for ((name, value) in props) {
            if (name == "LastChange") {
                val lc = LastChangeParser.parse(value)
                if (lc.transportState != null) {
                    Log.i(TAG, "[EVENT] TransportState=${lc.transportState} from=$remote")
                }
                if (eventServiceUrl != null) {
                    nowSession.applyEvent(eventServiceUrl, lc.transportState)
                    // 进度校准：用设备报告的时长/位置覆盖本地 tick
                    nowSession.syncProgress(
                        positionSec = lc.relativeTimePosition?.let(::hmsToSec),
                        durationSec = lc.currentTrackDuration?.let(::hmsToSec)
                    )
                    // 音量：LastChange 里可能带（RenderingControl 事件）
                    lc.volume?.toIntOrNull()?.let { nowSession.syncVolume(it) }
                }
            }
        }
    }

    /** "HH:MM:SS" / "MM:SS" -> 秒；解析失败返回 null */
    private fun hmsToSec(hms: String): Long? {
        val parts = hms.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** 回调地址：由当前 IP + 事件服务器端口拼出；服务器没起返回 null */
    val eventCallbackUrl: String?
        get() {
            val ip = LocalIp.ipv4() ?: return null
            if (eventServer.port == 0) return null
            return "http://$ip:${eventServer.port}/upnp/event/cb"
        }

    // ------------------------------------------------------------------
    // UI 状态与一次性消息
    // ------------------------------------------------------------------

    data class UiMessage(val text: String, val isError: Boolean = false)
    private val _messages = MutableSharedFlow<UiMessage>()
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun postMessage(text: String, isError: Boolean = false) {
        _messages.tryEmit(UiMessage(text, isError))
    }

    data class UiState(
        val scanning: Boolean = false,
        val statusText: Int? = null,
        val statusOverride: String? = null,
        val deviceCount: Int = 0,
        val nowPlayingActive: Boolean = false,
        val nowPlayingTitle: String = "",
        val nowPlayingDevice: String = "",
        // ---- 第 7 课 C：曲目元数据（歌手/专辑/封面） ----
        val nowPlayingArtist: String = "",
        val nowPlayingAlbum: String = "",
        val nowPlayingArtUrl: String = "",
        val nowPlayingPlaying: Boolean = false,
        val nowPlayingHasRc: Boolean = false,
        val positionSec: Long = 0L,
        val durationSec: Long = 0L,
        val seekable: Boolean = false,
        /** 当前音量 0-100，null = 未知（无 RenderingControl 或未读到） */
        val volume: Int? = null,
        /** 播放队列：还有几首待播（连播用） */
        val queuePendingCount: Int = 0,
        /** 播放队列：当前这首的标题（队列来源时显示） */
        val queueCurrentTitle: String = "",
        /** 播放队列：是否已有内容（决定首页"队列入口"显不显示） */
        val queueHasItems: Boolean = false,
        /** 播放历史：记录条数（有内容时显示"最近播放"入口） */
        val historyCount: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun setStatusOverride(text: String?) {
        _uiState.update { it.copy(statusOverride = text) }
    }

    // ------------------------------------------------------------------
    // 设备列表行（registry.listener 驱动）
    // ------------------------------------------------------------------

    private val _deviceRows = MutableStateFlow<List<DeviceListItem>>(emptyList())
    val deviceRows: StateFlow<List<DeviceListItem>> = _deviceRows.asStateFlow()

    private fun rebuildDeviceRows() {
        val mediaServer = ArrayList<Entry>()
        val others = ArrayList<Entry>()
        for (entry in registry.all()) {
            val device = entry.device
            if (device != null && isMediaServer(device)) mediaServer.add(entry)
            else others.add(entry)
        }
        // 收藏置顶（各自分组内）；未收藏的保持发现顺序（排序稳定）
        mediaServer.sortByDescending { isFavoriteEntry(it) }
        others.sortByDescending { isFavoriteEntry(it) }
        val rows = ArrayList<DeviceListItem>()
        fun appendGroup(title: String, group: List<Entry>) {
            if (group.isEmpty()) return
            rows += DeviceListItem.Header(title)
            for (entry in group) {
                rows += deviceItemOf(entry)
            }
        }
        appendGroup("媒体服务器（${mediaServer.size}）", mediaServer)
        appendGroup("其他设备（${others.size}）", others)
        _deviceRows.value = rows
        _uiState.update { it.copy(deviceCount = registry.size) }
        // 设备列表每次变化后，试着恢复"上次播放"的控制条（设备刚回来时）
        restoreLastSessionIfDeviceBack()
    }

    /** 组装一行设备卡片（名称 + 类型·型号·IP 小字） */
    private fun deviceItemOf(entry: Entry): DeviceListItem.DeviceItem {
        val device = entry.device
        if (device == null) {
            return DeviceListItem.DeviceItem(
                entryKey = entry.location,
                name = if (entry.failed) "（描述获取失败）" else "正在获取设备信息…",
                sub = entry.ip.ifEmpty { "IP 未知" }
            )
        }
        val star = if (isFavoriteEntry(entry)) "⭐ " else ""
        val alias = bookmarks.alias(deviceIdOf(entry))
        val shown = alias ?: device.friendlyName.ifEmpty { "（未命名）" }
        val kind = when {
            isMediaServer(device) -> "媒体服务器"
            DlnaPlayer.isRenderer(device) -> "播放器"
            else -> "设备"
        }
        val sub = buildString {
            append(kind)
            val model = device.modelName.trim()
            if (model.isNotEmpty() && !device.friendlyName.contains(model)) append(" · $model")
            if (entry.ip.isNotEmpty()) append(" · ${entry.ip}")
            if (alias != null && device.friendlyName.isNotBlank()) append(" · 原名${device.friendlyName}")
        }
        return DeviceListItem.DeviceItem(entryKey = entry.location, name = "$star$shown", sub = sub)
    }

    /** 设备详细信息（IP/UDN/服务/URL，调试用，UI 弹窗展示） */
    fun deviceInfoText(entry: Entry): String {
        val device = entry.device
        val sb = StringBuilder()
        sb.appendLine("名称：${shownNameOf(entry)}")
        if (device != null) {
            if (bookmarks.alias(deviceIdOf(entry)) != null) sb.appendLine("原名：${device.friendlyName}")
            sb.appendLine("类型：${device.deviceType}")
            sb.appendLine("UDN：${device.udn.ifEmpty { "（无）" }}")
            sb.appendLine("厂商：${device.manufacturer.ifEmpty { "?" }}  型号：${device.modelName.ifEmpty { "?" }}")
            sb.appendLine("服务(${device.services.size})：")
            for (svc in device.services) {
                sb.appendLine("  • ${svc.serviceType.substringAfterLast(':')}")
                sb.appendLine("      控制: ${svc.controlUrl}")
                if (svc.eventSubUrl.isNotBlank()) sb.appendLine("      事件: ${svc.eventSubUrl}")
            }
        } else {
            sb.appendLine("（设备描述尚未加载成功）")
            sb.appendLine("USN：${entry.usn ?: "?"}")
        }
        sb.appendLine("地址：${entry.location}")
        sb.append("收藏：${if (isFavoriteEntry(entry)) "是" else "否"}")
        return sb.toString()
    }

    private fun isMediaServer(device: com.example.myupnp.model.UpnpDevice): Boolean {
        return device.deviceType.contains("MediaServer", ignoreCase = true) ||
            device.services.any { it.serviceType.contains("ContentDirectory", ignoreCase = true) }
    }

    // ------------------------------------------------------------------
    // 正在播放状态同步 + 离线清理
    // ------------------------------------------------------------------

    private fun syncNowPlayingState() {
        val np = nowSession.current
        _uiState.update {
            it.copy(
                nowPlayingActive = np != null,
                nowPlayingTitle = np?.title.orEmpty(),
                nowPlayingDevice = np?.deviceName.orEmpty(),
                nowPlayingArtist = np?.artist.orEmpty(),
                nowPlayingAlbum = np?.album.orEmpty(),
                nowPlayingArtUrl = np?.artUrl.orEmpty(),
                nowPlayingPlaying = np?.playing == true,
                nowPlayingHasRc = np?.rc != null,
                positionSec = np?.positionSec ?: 0L,
                durationSec = np?.durationSec ?: 0L,
                seekable = np?.seekable == true,
                volume = np?.volume,
                queuePendingCount = playbackQueue.pendingCount,
                queueCurrentTitle = playbackQueue.current?.title.orEmpty(),
                queueHasItems = playbackQueue.hasActivity,
            )
        }
        // 有会话就记忆"上次在播什么"（重启后恢复用）
        if (np != null) {
            saveLastSession(
                deviceKey = np.deviceKey,
                deviceName = np.deviceName,
                title = np.title
            )
        } else {
            clearLastSession()
        }
    }

    /** 队列记账变化后刷新 UI 里的队列角标（只动队列字段），并顺带持久化 */
    private fun syncQueueUi() {
        _uiState.update {
            it.copy(
                queuePendingCount = playbackQueue.pendingCount,
                queueCurrentTitle = playbackQueue.current?.title.orEmpty(),
                queueHasItems = playbackQueue.hasActivity,
            )
        }
        persistQueue()
    }

    // ------------------------------------------------------------------
    // 第 7 课 A：队列管理（删除/移动/插队）与持久化
    // ------------------------------------------------------------------

    /** 启动时调用一次：恢复上次没播完的"待播列表" */
    fun prepareRestoredQueue() {
        if (queueRestored) return
        queueRestored = true
        val items = runCatching { readPendingFromPrefs() }.getOrDefault(emptyList())
        if (items.isNotEmpty()) {
            playbackQueue.restorePending(items)
            Log.i(TAG, "[QUEUE] 已恢复 ${items.size} 首待播")
        }
        syncQueueUi()
    }

    /** "下一首播放"：插到队首（同曲先移除），当前这首播完自动播它 */
    fun queuePlayNext(item: MediaItem) {
        playbackQueue.playNext(item)
        syncQueueUi()
        postMessage("已插到队首：当前这首播完就播《${item.title}》")
    }

    /** 删除待播中的某一首 */
    fun queueRemovePendingAt(index: Int) {
        if (index !in 0 until playbackQueue.pendingCount) return
        val name = playbackQueue.pendingAt(index)?.title ?: return
        playbackQueue.removePendingAt(index)
        syncQueueUi()
        postMessage("已从队列删除《$name》")
    }

    /** 上移(-1)/下移(+1)待播中的某一首 */
    fun queueMovePendingAt(index: Int, delta: Int) {
        if (index !in 0 until playbackQueue.pendingCount) return
        playbackQueue.movePending(index, delta)
        syncQueueUi()
    }

    /** 把"待播列表"写进 SharedPreferences（JSON） */
    private fun persistQueue() {
        val arr = JSONArray()
        for (item in playbackQueue.snapshotPending()) {
            arr.put(
                JSONObject().apply {
                    put("i", item.id)
                    put("t", item.title)
                    put("r", item.resUrl)
                    put("a", item.artist)
                    put("l", item.album)
                    put("c", item.artUrl)
                }
            )
        }
        queuePrefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    private fun readPendingFromPrefs(): List<MediaItem> {
        val raw = queuePrefs.getString(KEY_QUEUE, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<MediaItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val res = o.optString("r")
            if (res.isBlank()) continue
            out += MediaItem(
                id = o.optString("i"),
                title = o.optString("t").ifEmpty { "（未命名）" },
                resUrl = res,
                artist = o.optString("a"),
                album = o.optString("l"),
                artUrl = o.optString("c")
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // "记忆上次播放"：持久化上次会话，重启/重扫后自动恢复控制条
    // ------------------------------------------------------------------

    private val prefs =
        getApplication<Application>().getSharedPreferences("playback_memory", Context.MODE_PRIVATE)

    private data class LastSession(val deviceKey: String?, val deviceName: String, val title: String)

    private fun saveLastSession(deviceKey: String?, deviceName: String, title: String) {
        prefs.edit()
            .putString(KEY_DEVICE_KEY, deviceKey)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putString(KEY_TITLE, title)
            .apply()
    }

    private fun loadLastSession(): LastSession? {
        val key = prefs.getString(KEY_DEVICE_KEY, null) ?: return null
        val name = prefs.getString(KEY_DEVICE_NAME, null) ?: return null
        val title = prefs.getString(KEY_TITLE, null) ?: return null
        return LastSession(key, name, title)
    }

    private fun clearLastSession() {
        prefs.edit().clear().apply()
    }

    /**
     * 尝试恢复上次播放会话：目标设备已被重新发现时，重建控制条并查真实状态。
     * 不自动播放 —— 只恢复"上次在播什么"的显示，实际状态用 GetTransportInfo 确认。
     */
    fun restoreLastSessionIfDeviceBack() {
        if (nowSession.isActive) return           // 已有会话
        val last = loadLastSession() ?: return
        // 用 deviceKey（=LOCATION）精确定位；找不到再按 friendlyName 试
        val entry = last.deviceKey?.let { registry[it] }
            ?: registry.all().firstOrNull { it.device?.friendlyName == last.deviceName }
            ?: return
        val device = entry.device ?: return       // 描述还没下来
        val avt = DlnaPlayer.avTransportOf(device) ?: return
        val rc = DlnaPlayer.renderingControlOf(device)
        Log.i(TAG, "[MEM] 设备回来了，恢复上次播放显示: ${last.title} @ ${last.deviceName}")
        nowSession.begin(
            deviceName = last.deviceName,
            avt = avt,
            rc = rc,
            title = last.title,
            deviceKey = entry.location,
            playing = false  // 先不假定在播，靠 GetTransportInfo 确认
        )
        nowSession.refreshTransportState() // 问设备真实状态，点亮播放/暂停图标
    }


    private fun clearNowPlayingIfDeviceGone() {
        if (!nowSession.isActive) return
        val playingHost = nowSession.playingHost()
        val stillAlive = registry.all().any { e ->
            val h = runCatching { URL(e.location).host }.getOrNull()
            h == playingHost
        }
        if (!stillAlive) {
            nowSession.end("设备已离线")
            // 让用户知道"不是 App 抽风"：播放设备掉线了
            postMessage("播放设备已离线，已收起播放控制条")
        }
    }

    // ------------------------------------------------------------------
    // 第 7 课 B：播放队列（点歌排队 + 播完自动连播）
    // ------------------------------------------------------------------
    // 队列的记账都在主线程（UI 点击 / 事件回调 / onTrackEnded 都经主线程），
    // 网络推送丢 controlExecutor，成功后 mainHandler 回主线程再记账。

    /** 点歌 -> "加入队列"：排到队尾，当前这首播完自动连播 */
    fun queueEnqueue(item: MediaItem) {
        playbackQueue.enqueue(item)
        syncQueueUi()
        postMessage(
            if (nowSession.isActive)
                "已加入队列（${playbackQueue.pendingCount} 首待播）：当前这首播完自动连播"
            else
                "已加入队列（${playbackQueue.pendingCount} 首）：先播放一首，之后自动接上"
        )
    }

    /** 点歌 -> "播放"且推送成功后调用：记成"当前这首"，已排队的歌不受影响 */
    fun queueOnPlayed(item: MediaItem) {
        playbackQueue.commitPlayNow(item)
        syncQueueUi()
    }

    /** 控制条"下一首"：跳过当前，直接播排队的第一首 */
    fun queueNextItem() {
        val item = playbackQueue.nextUp
        if (item == null) {
            postMessage("没有下一首了（队列已播完）")
            return
        }
        pushQueueItem(item) {
            playbackQueue.commitNext(item)
            syncQueueUi()
        }
    }

    /** 控制条"上一首"：回放最近播完的那首（当前这首放回队首，播完再接上） */
    fun queuePreviousItem() {
        val item = playbackQueue.previousUp
        if (item == null) {
            postMessage("没有上一首（前面还没播过歌）")
            return
        }
        pushQueueItem(item) {
            playbackQueue.commitPrevious(item)
            syncQueueUi()
        }
    }

    /** 队列列表里点第 index 首：立即切到它播（从待播里拿走） */
    fun queuePlayPendingAt(index: Int) {
        val item = playbackQueue.pendingAt(index)
        if (item == null) {
            postMessage("队列里没有这一首")
            return
        }
        pushQueueItem(item) {
            playbackQueue.commitPlayNow(item)
            syncQueueUi()
        }
    }

    fun queueClear() {
        playbackQueue.clear()
        syncQueueUi()
        postMessage("已清空播放队列")
    }

    /** 供 UI 弹队列列表用（主线程读取，安全） */
    fun queuePendingSnapshot(): List<MediaItem> = playbackQueue.snapshotPending()

    /** 一首自然播完 -> 自动连播队首 */
    private fun autoAdvanceQueue() {
        val item = playbackQueue.nextUp
        if (item == null) {
            if (playbackQueue.hasActivity) postMessage("队列已播完")
            return
        }
        pushQueueItem(item) {
            playbackQueue.commitNext(item)
            syncQueueUi()
        }
    }

    /**
     * 把队列里的一首推给"当前会话"钉住的播放器（同一台设备继续播）。
     * 推送成功才记账/切会话；失败保留队列原状。
     */
    private fun pushQueueItem(item: MediaItem, onSuccess: () -> Unit) {
        val np = nowSession.current
        if (np == null) {
            postMessage("还没有正在播放的设备：先推一首歌开始播，连播才有目标", isError = true)
            return
        }
        Log.i(TAG, "[QUEUE] 推送队列歌曲: ${item.title} → ${np.deviceName}")
        controlExecutor.execute {
            val results = DlnaPlayer.pushAndPlay(np.avt, item.resUrl, item.title)
            mainHandler.post {
                if (results.lastOrNull()?.second?.success == true) {
                    onSuccess()
                    nowSession.begin(
                        deviceName = np.deviceName,
                        avt = np.avt,
                        rc = np.rc,
                        title = item.title,
                        deviceKey = np.deviceKey,
                        artist = item.artist,
                        album = item.album,
                        artUrl = item.artUrl
                    )
                    noteHistoryPlayed(item) // 第 7 课 D：连播/下一首也算进"最近播放"
                    postMessage("▶ 正在播放：《${item.title}》")
                } else {
                    postMessage(
                        "推送队列歌曲失败：${results.lastOrNull()?.second?.summary()}",
                        isError = true
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 第 7 课 D：播放历史（最近播放，持久化到 PlayHistory）
    // ------------------------------------------------------------------

    /** 推送成功后记录进"最近播放"（UI 重播需要还原成 MediaItem 再走选设备流程） */
    fun noteHistoryPlayed(item: MediaItem) {
        playHistory.push(
            title = item.title,
            resUrl = item.resUrl,
            artist = item.artist,
            album = item.album,
            artUrl = item.artUrl
        )
        _uiState.update { it.copy(historyCount = playHistory.size) }
    }

    fun historyEntries(): List<PlayHistory.Entry> = playHistory.entries()

    fun historyRemoveAt(position: Int) {
        playHistory.removeAt(position)
        _uiState.update { it.copy(historyCount = playHistory.size) }
    }

    fun historyClear() {
        playHistory.clear()
        _uiState.update { it.copy(historyCount = 0) }
    }

    // ------------------------------------------------------------------
    // 第 7 课 B：本地文件推送（手机起 HTTP 服务供渲染器拉流）
    // ------------------------------------------------------------------

    /**
     * 启动本地文件 HTTP 服务并返回可推给渲染器的 URL。
     * 后台启动，结果经主线程回调。
     * @param uri  用户选中的本地文件（content://）
     * @param name 文件名（用于 Content-Type 与 URL）
     */
    fun serveLocalFile(uri: Uri, name: String, onDone: (url: String?, error: String?) -> Unit) {
        fetchExecutor.execute {
            val started = fileServer.start()
            fileServer.setCurrent(uri, name)
            val ip = LocalIp.ipv4()
            val url = if (started && ip != null && fileServer.port > 0) {
                "http://$ip:${fileServer.port}/" + Uri.encode(name)
            } else {
                null
            }
            mainHandler.post {
                if (url != null) onDone(url, null)
                else onDone(null, "本地文件服务启动失败：请确认已连 Wi-Fi")
            }
        }
    }

    // ------------------------------------------------------------------
    // 第 7 课 E：设备收藏 / 别名（持久化到 DeviceBookmarks）
    // ------------------------------------------------------------------

    /**
     * 设备稳定身份：UDN > USN 的 uuid > LOCATION（最后兜底，重启可能变 IP）。
     * 收藏与别名都以它为键 —— 设备 IP 变了仍能认回同一台。
     */
    fun deviceIdOf(entry: Entry): String {
        val udn = entry.device?.udn?.trim().orEmpty()
        if (udn.isNotEmpty()) return udn
        val uuid = entry.usn?.substringBefore("::")?.trim().orEmpty()
        if (uuid.isNotEmpty()) return uuid
        return entry.location
    }

    fun isFavoriteEntry(entry: Entry): Boolean = bookmarks.isFavorite(deviceIdOf(entry))

    /** 设备对外显示的名字：别名优先，其次 friendlyName */
    fun shownNameOf(entry: Entry): String {
        val alias = bookmarks.alias(deviceIdOf(entry))
        if (alias != null) return alias
        val friendly = entry.device?.friendlyName?.trim().orEmpty()
        return when {
            friendly.isNotEmpty() -> friendly
            entry.device == null -> "（等待描述）"
            else -> "（未命名）"
        }
    }

    /** "选设备播放"用：收藏在前，其余保持发现顺序 */
    fun deviceEntriesFavoritesFirst(): List<Entry> {
        val list = registry.all().toMutableList()
        list.sortByDescending { isFavoriteEntry(it) }
        return list
    }

    /**
     * 免费版收藏上限（收藏功能属于"设备管理增强"，可挂 FeatureId.DEVICE_LIMIT）。
     * 现在 EverythingFreeGate 恒放行 → 不限；将来换真实 Gate 后，免费用户
     * 超上限时这里会自动弹升级提示（逻辑已就位）。
     */
    private val freeFavoriteLimit = 3

    fun toggleFavorite(entryKey: String) {
        val entry = registry[entryKey] ?: return
        val id = deviceIdOf(entry)
        val name = shownNameOf(entry)
        val favorite = bookmarks.isFavorite(id)
        if (!favorite) {
            // 收费口子：超上限且未解锁才拦（EverythingFreeGate 下永不拦）
            val overLimit = bookmarks.favoriteTotal() >= freeFavoriteLimit
            if (overLimit && !guardFeature(FeatureId.DEVICE_LIMIT) { msg ->
                    postMessage("$msg（免费版最多收藏 $freeFavoriteLimit 台）", isError = true)
                }) return
        }
        bookmarks.setFavorite(id, !favorite)
        postMessage(if (!favorite) "已收藏《$name》，列表置顶 ⭐" else "已取消收藏《$name》")
        rebuildDeviceRows()
    }

    fun renameDevice(entryKey: String, rawName: String) {
        val entry = registry[entryKey] ?: return
        val id = deviceIdOf(entry)
        val name = rawName.trim()
        if (name.isEmpty()) {
            postMessage("名称不能为空（想恢复原名请用「恢复原名」）", isError = true)
            return
        }
        if (name.length > 30) {
            postMessage("别名最多 30 个字", isError = true)
            return
        }
        bookmarks.setAlias(id, name)
        postMessage("已重命名为《$name》")
        rebuildDeviceRows()
    }

    fun clearDeviceAlias(entryKey: String) {
        val entry = registry[entryKey] ?: return
        bookmarks.clearAlias(deviceIdOf(entry))
        postMessage("已恢复原名《${entry.device?.friendlyName ?: ""}》")
        rebuildDeviceRows()
    }

    // ------------------------------------------------------------------
    // 收费拦截示例
    // ------------------------------------------------------------------

    fun guardFeature(feature: com.example.myupnp.core.FeatureId, onDenied: (String) -> Unit): Boolean {
        if (featureGate.canUse(feature)) return true
        onDenied(featureGate.upgradeMessage(feature))
        return false
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /** 供 Activity 在 onDestroy 时调用（通知网络状态结束） */
    fun onHostDestroyed() {
        userWantsScan = false
        stopHeartbeat()
        discovery.stop()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        subManager.unsubscribeAll()
        eventServer.stop()
        fileServer.stop()
    }

    override fun onCleared() {
        stopHeartbeat()
        discovery.stop()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        subManager.unsubscribeAll()
        eventServer.stop()
        fileServer.stop()
        mainHandler.removeCallbacksAndMessages(null)
        fetchExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        pollExecutor.shutdownNow()
        Log.i(TAG, "[VM] MainViewModel onCleared")
        super.onCleared()
    }

    companion object {
        private const val TAG = "MyUPNP"
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        const val DEVICE_STALE_MS = 45_000L
        const val NET_RESTART_DELAY_MS = 1_500L

        /** 兜底轮询节拍；距上次事件超过新鲜阈值才真正发查询 */
        const val POLL_TICK_MS = 5_000L
        const val POLL_EVENT_FRESH_MS = 15_000L

        // 记忆上次播放（SharedPreferences key）
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_TITLE = "title"

        // 队列持久化
        private const val KEY_QUEUE = "pending"
    }
}
