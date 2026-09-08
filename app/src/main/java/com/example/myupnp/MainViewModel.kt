package com.example.myupnp

import androidx.lifecycle.ViewModel
import com.example.myupnp.core.EverythingFreeGate
import com.example.myupnp.core.FeatureGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * MainViewModel —— MVVM 迁移的第一步
 * ------------------------------------------------------------------
 * 目标：把"UI 要显示什么状态"集中到这里，Activity 只负责
 *   a) 把用户操作转发进来（onStartScan / onStopScan / ...）
 *   b) 观察 [uiState] 渲染界面
 *
 * 迁移纪律（分批进行，见 docs/07-代码架构.md）：
 *   第 1 批（本轮）：UI 状态进 StateFlow，Activity 开始观察；
 *   第 2 批：把 DeviceRegistry / SubscriptionManager / NowPlayingSession
 *           连同 Executor、Handler 收进本类（Activity 不再自己持有）；
 *   第 3 批：网络感知、心跳定时器 → 协程化（viewModelScope）。
 *
 * 收费口子：[featureGate] 集中在此注入，业务层统一走它。
 */
class MainViewModel : ViewModel() {

    /** 功能门：免费全开，未来换订阅实现（见 core/FeatureGate.kt） */
    val featureGate: FeatureGate = EverythingFreeGate

    // ------------------------------------------------------------------
    // UI 状态
    // ------------------------------------------------------------------

    data class UiState(
        /** 是否正在扫描 */
        val scanning: Boolean = false,
        /** 状态栏文案：优先用 statusOverride（如 "Wi-Fi 断开…"），否则 statusText 资源 */
        val statusText: Int? = null,
        val statusOverride: String? = null,
        /** 设备列表标题计数 */
        val deviceCount: Int = 0,
        /** 正在播放摘要 */
        val nowPlayingActive: Boolean = false,
        val nowPlayingTitle: String = "",
        val nowPlayingDevice: String = "",
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun updateUi(transform: (UiState) -> UiState) {
        _uiState.update(transform)
    }

    // ---- 常用状态改口（第 1 批示范） ----

    fun setScanning(scanning: Boolean, statusText: Int) {
        _uiState.update { it.copy(scanning = scanning, statusText = statusText) }
    }

    fun setStatusOverride(text: String?) {
        _uiState.update { it.copy(statusOverride = text) }
    }

    fun setDeviceCount(count: Int) {
        _uiState.update { it.copy(deviceCount = count) }
    }

    fun setNowPlayingSummary(active: Boolean, title: String, device: String) {
        _uiState.update {
            it.copy(
                nowPlayingActive = active,
                nowPlayingTitle = title,
                nowPlayingDevice = device
            )
        }
    }

    /** 示例：收费功能拦截。将来在需要付费的功能入口调用 */
    fun guardFeature(feature: com.example.myupnp.core.FeatureId, onDenied: (String) -> Unit): Boolean {
        if (featureGate.canUse(feature)) return true
        onDenied(featureGate.upgradeMessage(feature))
        return false
    }
}
