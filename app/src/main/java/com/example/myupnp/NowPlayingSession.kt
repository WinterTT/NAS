package com.example.myupnp

import android.os.Handler
import android.util.Log
import com.example.myupnp.model.UpnpService
import com.example.myupnp.soap.SoapCaller
import java.net.URL
import java.util.concurrent.ExecutorService

/**
 * 播放会话（第 7 课 A 的"正在播放"，重构后独立成类）
 * ------------------------------------------------------------------
 * 职责：钉住"当前在往哪台设备播什么"，并提供传输控制动作：
 *   - begin()     建立会话（通常推送成功后调用）
 *   - toggle()    播放/暂停切换（对 AVTransport 发 SOAP）
 *   - stop()      停止播放但保留会话（控制条不消失）
 *   - end()       彻底结束会话（设备离线/清空时）
 *   - applyEvent() 用 GENA 事件纠正真实播放状态
 *
 * 本类不碰 View —— 状态一变通过 [listener.onChanged] 通知 UI 刷新。
 * 网络在 [controlExecutor]，结果回 [mainHandler] 后更新状态并回调。
 */
class NowPlayingSession(
    private val mainHandler: Handler,
    private val controlExecutor: ExecutorService,
    private val listener: Listener
) {

    interface Listener {
        /** 会话内容/播放状态变化，UI 重绘控制条 */
        fun onChanged(session: NowPlayingSession)
    }

    /** 一次播放会话的快照 */
    data class NowPlaying(
        val deviceName: String,
        val avt: UpnpService,      // AVTransport：Play/Pause/Stop
        val rc: UpnpService?,      // RenderingControl：音量（可能没有）
        val title: String,
        var playing: Boolean = false
    )

    private var session: NowPlaying? = null

    val current: NowPlaying? get() = session
    val isActive: Boolean get() = session != null
    val playing: Boolean get() = session?.playing == true

    /** 建立会话并默认标记为播放中（事件后续会纠正） */
    fun begin(deviceName: String, avt: UpnpService, rc: UpnpService?, title: String) {
        session = NowPlaying(
            deviceName = deviceName,
            avt = avt,
            rc = rc,
            title = title,
            playing = true
        )
        Log.i(TAG, "[NOW] 播放会话: $title @ $deviceName")
        listener.onChanged(this)
    }

    /** 播放/暂停切换 */
    fun toggle() {
        val np = session ?: return
        val action = if (np.playing) "Pause" else "Play"
        Log.i(TAG, "[NOW] ${if (action == "Pause") "暂停" else "播放"} @ ${np.deviceName}")
        controlExecutor.execute {
            val args = if (action == "Play")
                mapOf("InstanceID" to "0", "Speed" to "1")
            else
                mapOf("InstanceID" to "0")
            val r = SoapCaller.call(np.avt.controlUrl, np.avt.serviceType, action, args)
            mainHandler.post {
                if (r.success) {
                    np.playing = action == "Play" // 乐观更新，事件会再纠正
                    listener.onChanged(this@NowPlayingSession)
                } else {
                    Log.w(TAG, "[NOW!] $action 失败: ${r.summary()}")
                }
            }
        }
    }

    /** 停止播放但保留会话（控制条不消失，图标回到 ▶） */
    fun stop() {
        val np = session ?: return
        Log.i(TAG, "[NOW] 停止播放 @ ${np.deviceName}（保留会话）")
        controlExecutor.execute {
            SoapCaller.call(
                np.avt.controlUrl, np.avt.serviceType, "Stop",
                mapOf("InstanceID" to "0")
            )
        }
        np.playing = false
        listener.onChanged(this)
    }

    /** 彻底结束会话（设备离线/清空时调用） */
    fun end(reason: String) {
        Log.i(TAG, "[NOW] 结束会话: $reason")
        session = null
        listener.onChanged(this)
    }

    /** 用 GENA 事件纠正播放状态（TransportState） */
    fun applyEvent(avtEventSubUrl: String, transportState: String?) {
        val np = session ?: return
        if (np.avt.eventSubUrl != avtEventSubUrl) return // 不是当前播放设备/服务
        transportState?.let { state ->
            np.playing = state == "PLAYING"
            listener.onChanged(this)
        }
    }

    /** 正在播放的设备地址（用于"设备是否还在线"判断） */
    fun playingHost(): String? =
        session?.avt?.controlUrl?.let { runCatching { URL(it).host }.getOrNull() }

    companion object {
        private const val TAG = "MyUPNP"
    }
}
