package com.example.myupnp

import android.os.Handler
import android.util.Log
import com.example.myupnp.dlna.DlnaPlayer
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

        /** 一首自然播完（不是手动停止），供队列自动连播下一首 */
        fun onTrackEnded(session: NowPlayingSession) {}
    }

    /** 一次播放会话的快照 */
    data class NowPlaying(
        val deviceName: String,
        val avt: UpnpService,      // AVTransport：Play/Pause/Stop/Seek
        val rc: UpnpService?,      // RenderingControl：音量（可能没有）
        val title: String,
        /** 设备在注册表里的 key（=LOCATION），用于"记忆恢复"时重新定位 */
        val deviceKey: String? = null,
        // ---- 第 7 课 C：曲目元数据（浏览曲库时就有，推送时带过来） ----
        val artist: String = "",
        val album: String = "",
        /** 专辑封面地址（已绝对化）；空 = 没有 */
        val artUrl: String = "",
        var playing: Boolean = false,
        // ---- 进度（秒），由 GENA 事件校准，本地 tick 推进 ----
        var positionSec: Long = 0L,
        var durationSec: Long = 0L,
        var seekable: Boolean = false,   // 是否支持 Seek（有总时长才可拖）
        // ---- 音量（0-100），由 GENA 事件 / GetVolume 更新 ----
        var volume: Int? = null          // null = 未知
    )

    private var session: NowPlaying? = null
    private var tickPending = 0L  // 累计要补的播放秒数（毫秒精度）

    /** 上一次事件里的播放状态（用于判断"播完"） */
    private var lastWasPlaying = false

    /** 用户手动点了停止（避免被误判成"自然播完"而自动连播） */
    @Volatile
    private var userStopRequested = false

    /**
     * 刚 begin() 时设备可能先回一条旧的 STOPPED 事件；
     * 等看到首个 PLAYING 之后，才允许把 STOPPED 当"自然播完"。
     */
    @Volatile
    private var suppressEndUntilPlaying = false

    /** 本地兜底：连续超过片尾这么多秒仍无 STOPPED 事件 -> 判定播完（部分设备 GENA 不可靠） */
    private var endOverrunSec = 0L

    val current: NowPlaying? get() = session
    val isActive: Boolean get() = session != null
    val playing: Boolean get() = session?.playing == true

    /** 建立会话并默认标记为播放中（事件后续会纠正） */
    fun begin(
        deviceName: String,
        avt: UpnpService,
        rc: UpnpService?,
        title: String,
        deviceKey: String? = null,
        playing: Boolean = true,
        artist: String = "",
        album: String = "",
        artUrl: String = ""
    ) {
        session = NowPlaying(
            deviceName = deviceName,
            avt = avt,
            rc = rc,
            title = title,
            deviceKey = deviceKey,
            artist = artist,
            album = album,
            artUrl = artUrl,
            playing = playing
        )
        Log.i(TAG, "[NOW] 播放会话: $title @ $deviceName")
        lastWasPlaying = playing
        userStopRequested = false
        suppressEndUntilPlaying = playing // 声称在播：先等一次真实 PLAYING 事件
        endOverrunSec = 0
        listener.onChanged(this)
        if (rc != null) refreshVolume() // 建立会话后主动问一次当前音量
    }

    /**
     * 主动问设备一次当前传输状态（GetTransportInfo）。
     * 用于"记忆上次播放"恢复后确认设备现在到底在播/暂停/停止。
     */
    fun refreshTransportState() {
        val np = session ?: return
        controlExecutor.execute {
            val r = SoapCaller.call(
                np.avt.controlUrl, np.avt.serviceType, "GetTransportInfo",
                mapOf("InstanceID" to "0")
            )
            mainHandler.post {
                if (r.success) {
                    // out 参数：<CurrentTransportState>PLAYING|PAUSED|STOPPED</...>
                    val state = Regex("<CurrentTransportState>\\s*(\\w+)\\s*</CurrentTransportState>")
                        .find(r.body)?.groupValues?.get(1)
                    state?.let {
                        np.playing = it == "PLAYING"
                        Log.i(TAG, "[NOW] GetTransportInfo -> $it")
                        listener.onChanged(this@NowPlayingSession)
                    }
                }
            }
        }
    }

    /**
     * 播放时钟滴答：每 1 秒调用一次，播放中则 position+1。
     * 由 ViewModel 的协程驱动（不再用 Handler postDelayed）。
     */
    fun tick(stepMs: Long = 1_000L) {
        val np = session ?: return
        if (!np.playing) return
        tickPending += stepMs
        if (tickPending >= 1_000L) {
            val secs = tickPending / 1_000L
            tickPending -= secs * 1_000L
            np.positionSec += secs
            // 兜底判"播完"：有些设备不推送事件，超过片尾几秒仍无 STOPPED -> 视为播完
            if (np.durationSec > 10 && np.positionSec > np.durationSec + 3) {
                endOverrunSec += secs
            } else {
                endOverrunSec = 0
            }
            if (endOverrunSec >= 4 && !suppressEndUntilPlaying && !userStopRequested) {
                endOverrunSec = 0
                Log.i(TAG, "[NOW] 已超过片尾仍无停止事件，判定一首播完")
                np.playing = false
                lastWasPlaying = false
                listener.onTrackEnded(this@NowPlayingSession)
            }
            listener.onChanged(this)
        }
    }

    /** 更新当前音量（GENA 事件 / GetVolume 查询），null 表示未知 */
    fun syncVolume(volume: Int?) {
        val np = session ?: return
        volume?.let { np.volume = it.coerceIn(0, 100) }
        listener.onChanged(this)
    }

    /** 用 GENA 事件里的进度校准（优先于本地 tick） */
    fun syncProgress(positionSec: Long?, durationSec: Long?) {
        val np = session ?: return
        durationSec?.let { np.durationSec = it }
        positionSec?.let { np.positionSec = it }
        np.seekable = np.durationSec > 0
        // 进度回到片尾内 -> 兜底计数清零
        if (np.durationSec <= 0 || np.positionSec <= np.durationSec) endOverrunSec = 0
        listener.onChanged(this)
    }

    /** 主动问设备当前音量（GetVolume），用于刚建立会话/恢复时显示 */
    fun refreshVolume() {
        val rc = session?.rc ?: return
        controlExecutor.execute {
            val r = SoapCaller.call(
                rc.controlUrl, rc.serviceType, "GetVolume",
                mapOf("InstanceID" to "0", "Channel" to "Master")
            )
            mainHandler.post {
                if (r.success) {
                    val v = DlnaPlayer.parseCurrentVolume(r.body)
                    if (v >= 0) syncVolume(v)
                }
            }
        }
    }

    /** 跳转到指定秒（AVTransport Seek，REL_TIME 单位） */
    fun seekTo(targetSec: Long) {
        val np = session ?: return
        if (np.durationSec <= 0) return
        val target = targetSec.coerceIn(0, np.durationSec)
        Log.i(TAG, "[NOW] Seek → ${formatTime(target)} @ ${np.deviceName}")
        // 先乐观更新本地进度（设备执行后事件会再校准）
        np.positionSec = target
        listener.onChanged(this)
        controlExecutor.execute {
            val r = SoapCaller.call(
                np.avt.controlUrl, np.avt.serviceType, "Seek",
                mapOf(
                    "InstanceID" to "0",
                    "Unit" to "REL_TIME",
                    "Target" to formatTime(target)
                )
            )
            mainHandler.post {
                if (!r.success) {
                    Log.w(TAG, "[NOW!] Seek 失败: ${r.summary()}")
                }
            }
        }
    }

    /** 秒 -> "HH:MM:SS"（UPnP REL_TIME 需要） */
    private fun formatTime(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
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
                    if (action == "Play") {
                        userStopRequested = false   // 用户手动续播，之后播完仍算自然结束
                        suppressEndUntilPlaying = false
                        lastWasPlaying = true       // 已明确要求播放：之后的 STOPPED 视为播完
                    }
                    listener.onChanged(this@NowPlayingSession)
                } else {
                    Log.w(TAG, "[NOW!] $action 失败: ${r.summary()}")
                }
            }
        }
    }

    /** 停止播放但保留会话（控制条不消失，图标回到 ▶）。标记为"用户停止"，不触发连播 */
    fun stop() {
        val np = session ?: return
        Log.i(TAG, "[NOW] 停止播放 @ ${np.deviceName}（保留会话）")
        userStopRequested = true
        controlExecutor.execute {
            SoapCaller.call(
                np.avt.controlUrl, np.avt.serviceType, "Stop",
                mapOf("InstanceID" to "0")
            )
        }
        np.playing = false
        lastWasPlaying = false
        endOverrunSec = 0
        listener.onChanged(this)
    }

    /** 彻底结束会话（设备离线/清空时调用） */
    fun end(reason: String) {
        Log.i(TAG, "[NOW] 结束会话: $reason")
        session = null
        userStopRequested = false
        suppressEndUntilPlaying = false
        endOverrunSec = 0
        listener.onChanged(this)
    }

    /** 用 GENA 事件纠正播放状态（TransportState），并识别"一首自然播完" */
    fun applyEvent(avtEventSubUrl: String, transportState: String?) {
        val np = session ?: return
        if (np.avt.eventSubUrl != avtEventSubUrl) return // 不是当前播放设备/服务
        transportState?.let { state ->
            val newPlaying = state == "PLAYING"
            if (newPlaying) suppressEndUntilPlaying = false // 见到真实播放，解除屏蔽
            val endedByState = !newPlaying &&
                (state == "STOPPED" || state == "NO_MEDIA_PRESENT")
            val trackEnded = lastWasPlaying && endedByState &&
                !suppressEndUntilPlaying && !userStopRequested
            np.playing = newPlaying
            lastWasPlaying = if (trackEnded) false else newPlaying
            if (trackEnded) {
                endOverrunSec = 0
                Log.i(TAG, "[NOW] 一首播完（$state），可连播下一首")
                listener.onTrackEnded(this@NowPlayingSession)
            }
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
