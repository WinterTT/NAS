package com.example.myupnp

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import com.example.myupnp.core.EverythingFreeGate
import com.example.myupnp.core.FeatureGate
import com.example.myupnp.model.UpnpDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MainViewModel —— MVVM 迁移（第 2 批）
 * ------------------------------------------------------------------
 * 现在把"业务状态对象"全部收进本类管理生命周期：
 *   - 两个线程池 + 主线程 Handler（onCleared 统一释放）
 *   - DeviceRegistry（设备列表/心跳/描述）
 *   - SubscriptionManager（GENA 订阅）
 *   - NowPlayingSession（正在播放会话）
 *
 * Activity 通过私有 getter 访问这些对象（引用自动跟随），
 * 列表行与扫描/播放 UI 状态由 StateFlow 驱动渲染。
 *
 * 收费口子：[featureGate] 集中在此，业务层统一走它。
 */
class MainViewModel : ViewModel() {

    // ------------------------------------------------------------------
    // 线程基础设施（第 2 批：从 Activity 收编到这里）
    // ------------------------------------------------------------------

    val mainHandler: Handler = Handler(Looper.getMainLooper())

    val fetchExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "description-fetch").apply { isDaemon = true }
    }

    val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soap-control").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------
    // 业务状态对象（都持 Handler/Executor，生命周期归本类）
    // ------------------------------------------------------------------

    /** 功能门：免费全开，未来换订阅实现（见 core/FeatureGate.kt） */
    val featureGate: FeatureGate = EverythingFreeGate

    /** 设备注册表：增删变化会触发 [deviceRows] 重建 */
    val registry = DeviceRegistry(
        mainHandler = mainHandler,
        fetchExecutor = fetchExecutor,
        listener = object : DeviceRegistry.Listener {
            override fun onRegistryChanged() {
                rebuildDeviceRows()
            }
        }
    )

    /** 正在播放会话 */
    val nowSession = NowPlayingSession(
        mainHandler = mainHandler,
        controlExecutor = controlExecutor,
        listener = object : NowPlayingSession.Listener {
            override fun onChanged(session: NowPlayingSession) {
                syncNowPlayingState()
            }
        }
    )

    /** GENA 订阅管理：Info/Error 走一次性消息流（Activity 弹 Toast） */
    val subManager = SubscriptionManager(
        mainHandler = mainHandler,
        controlExecutor = controlExecutor,
        listener = object : SubscriptionManager.Listener {
            override fun onInfo(message: String) {
                postMessage(message, isError = false)
            }

            override fun onError(message: String) {
                postMessage(message, isError = true)
            }
        }
    )

    // ------------------------------------------------------------------
    // UI 状态
    // ------------------------------------------------------------------

    /** 一次性 UI 消息（Toast 用） */
    data class UiMessage(val text: String, val isError: Boolean = false)

    private val _messages = MutableSharedFlow<UiMessage>()
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun postMessage(text: String, isError: Boolean = false) {
        // SharedFlow 无订阅时会丢消息；主界面总是先 collect，这里容忍少量丢失
        _messages.tryEmit(UiMessage(text, isError))
    }

    data class UiState(
        val scanning: Boolean = false,
        val statusText: Int? = null,
        val statusOverride: String? = null,
        val deviceCount: Int = 0,
        // ---- 正在播放控制条 ----
        val nowPlayingActive: Boolean = false,
        val nowPlayingTitle: String = "",
        val nowPlayingDevice: String = "",
        val nowPlayingPlaying: Boolean = false,
        val nowPlayingHasRc: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun updateUi(transform: (UiState) -> UiState) {
        _uiState.update(transform)
    }

    fun setScanning(scanning: Boolean, statusText: Int) {
        _uiState.update { it.copy(scanning = scanning, statusText = statusText) }
    }

    fun setStatusOverride(text: String?) {
        _uiState.update { it.copy(statusOverride = text) }
    }

    // ------------------------------------------------------------------
    // 设备列表行（由 registry.listener 驱动重建）
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

        // 同步计数（标题用）
        _uiState.update { it.copy(deviceCount = registry.size) }
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

    /** 判断设备类型：MediaServer 或其它（DeviceRegistry 的分类逻辑） */
    private fun isMediaServer(device: UpnpDevice): Boolean {
        return device.deviceType.contains("MediaServer", ignoreCase = true) ||
            device.services.any { it.serviceType.contains("ContentDirectory", ignoreCase = true) }
    }

    // ------------------------------------------------------------------
    // 正在播放状态同步
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
            )
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

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        fetchExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        Log.i(TAG, "[VM] MainViewModel onCleared，线程池已释放")
        super.onCleared()
    }

    companion object {
        private const val TAG = "MyUPNP"
    }
}
