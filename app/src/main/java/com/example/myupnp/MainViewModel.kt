package com.example.myupnp

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myupnp.core.EverythingFreeGate
import com.example.myupnp.core.FeatureGate
import com.example.myupnp.dlna.DlnaPlayer
import com.example.myupnp.dlna.LastChangeParser
import com.example.myupnp.gena.EventProperties
import com.example.myupnp.gena.GenaClient
import com.example.myupnp.gena.LocalEventServer
import com.example.myupnp.gena.LocalIp
import com.example.myupnp.model.MediaItem
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
    val featureGate: FeatureGate = EverythingFreeGate

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
        val rows = ArrayList<DeviceListItem>()
        fun appendGroup(title: String, group: List<Entry>) {
            if (group.isEmpty()) return
            rows += DeviceListItem.Header(title)
            for (entry in group) {
                rows += DeviceListItem.DeviceItem(entry.location, formatDeviceText(entry))
            }
        }
        appendGroup("📦 MediaServer（${mediaServer.size}）", mediaServer)
        appendGroup("其他设备（${others.size}）", others)
        _deviceRows.value = rows
        _uiState.update { it.copy(deviceCount = registry.size) }
        // 设备列表每次变化后，试着恢复"上次播放"的控制条（设备刚回来时）
        restoreLastSessionIfDeviceBack()
    }

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
                nowPlayingPlaying = np?.playing == true,
                nowPlayingHasRc = np?.rc != null,
                positionSec = np?.positionSec ?: 0L,
                durationSec = np?.durationSec ?: 0L,
                seekable = np?.seekable == true,
                volume = np?.volume,
                queuePendingCount = playbackQueue.pendingCount,
                queueCurrentTitle = playbackQueue.current?.title.orEmpty(),
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

    /** 队列记账变化后刷新 UI 里的队列角标（只动队列字段） */
    private fun syncQueueUi() {
        _uiState.update {
            it.copy(
                queuePendingCount = playbackQueue.pendingCount,
                queueCurrentTitle = playbackQueue.current?.title.orEmpty(),
            )
        }
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
        if (!stillAlive) nowSession.end("设备已离线")
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
                        deviceKey = np.deviceKey
                    )
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
    }

    override fun onCleared() {
        stopHeartbeat()
        discovery.stop()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        subManager.unsubscribeAll()
        eventServer.stop()
        mainHandler.removeCallbacksAndMessages(null)
        fetchExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        Log.i(TAG, "[VM] MainViewModel onCleared")
        super.onCleared()
    }

    companion object {
        private const val TAG = "MyUPNP"
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        const val DEVICE_STALE_MS = 45_000L
        const val NET_RESTART_DELAY_MS = 1_500L

        // 记忆上次播放（SharedPreferences key）
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_TITLE = "title"
    }
}
