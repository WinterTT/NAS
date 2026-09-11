package com.example.myupnp

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.util.Log
import com.example.myupnp.model.MediaItem

/**
 * 本机音乐播放器（第 13 课：本机播放只处理音频，复用 App 的播放页）
 * ------------------------------------------------------------------
 * 用系统 MediaPlayer 播音频（http 或 content://）；视频交给系统播放器 App。
 * 状态通过 [Listener] 回到主线程，由 ViewModel 合成到 UiState，
 * 于是**同一个播放页/控制条**既能控制 DLNA 设备，也能控制本机播放。
 *
 * 细节：
 *   - URL 会做百分号编码（DLNA 服务器常返回带空格/中文的未转义地址）
 *   - 音量作用于手机媒体音量（AudioManager.STREAM_MUSIC）
 *   - 失败时给出可读原因（HTTP/解码器错误码）
 */
class LocalPlayer(
    private val mainHandler: Handler,
    private val listener: Listener
) {

    interface Listener {
        /** 状态变化（播放/暂停/进度/时长） */
        fun onLocalStateChanged()

        /** 一首播完（用于连播下一首） */
        fun onLocalCompleted()

        /** 出错（已给出可读原因） */
        fun onLocalError(message: String)
    }

    private var player: MediaPlayer? = null
    private var appContext: Context? = null

    /** 当前本机播放的曲目（null = 未在本机播放） */
    var current: MediaItem? = null
        private set

    val isActive: Boolean get() = current != null

    fun play(context: Context, item: MediaItem) {
        appContext = context.applicationContext
        stopInternal()
        current = item
        val mp = MediaPlayer()
        player = mp
        mp.setOnPreparedListener {
            Log.i(TAG, "[LOCAL] 准备完成，开始播放: ${item.title}")
            it.start()
            listener.onLocalStateChanged()
        }
        mp.setOnCompletionListener {
            Log.i(TAG, "[LOCAL] 播放完成: ${item.title}")
            listener.onLocalCompleted()
        }
        mp.setOnErrorListener { _, what, extra ->
            val reason = decodeError(what, extra)
            Log.w(TAG, "[LOCAL!] 播放失败 what=$what extra=$extra ($reason)")
            listener.onLocalError("本机播放失败：$reason")
            true
        }
        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            val url = item.resUrl
            if (url.startsWith("content://")) {
                mp.setDataSource(context, Uri.parse(url))
            } else {
                mp.setDataSource(encodeUrl(url))
            }
            mp.prepareAsync()
            listener.onLocalStateChanged()
        } catch (e: Exception) {
            Log.w(TAG, "[LOCAL!] 无法开始播放: ${e.message}")
            stopInternal()
            listener.onLocalError("无法开始播放：${e.message}")
        }
    }

    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun toggle() {
        val mp = player ?: return
        runCatching {
            if (mp.isPlaying) mp.pause() else mp.start()
        }
        listener.onLocalStateChanged()
    }

    fun stop() {
        val item = current
        stopInternal()
        current = null
        Log.i(TAG, "[LOCAL] 停止本机播放: ${item?.title ?: "-"}")
        listener.onLocalStateChanged()
    }

    fun positionSec(): Long =
        runCatching { (player?.currentPosition ?: 0) / 1000L }.getOrDefault(0L)

    fun durationSec(): Long =
        runCatching { (player?.duration ?: 0) / 1000L }.getOrDefault(0L)

    fun seekTo(sec: Long) {
        runCatching { player?.seekTo((sec * 1000L).toInt()) }
        listener.onLocalStateChanged()
    }

    /** 手机媒体音量（0-100） */
    fun volumePercent(): Int? {
        val ctx = appContext ?: return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolumePercent(percent: Int) {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) * max / 100)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        listener.onLocalStateChanged()
    }

    fun release() {
        stopInternal()
        current = null
    }

    private fun stopInternal() {
        runCatching {
            player?.setOnPreparedListener(null)
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            if (player?.isPlaying == true) player?.stop()
            player?.reset()
            player?.release()
        }
        player = null
    }

    /** MediaPlayer 错误码 → 人话 */
    private fun decodeError(what: Int, extra: Int): String = when {
        what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "手机不支持这个音频格式/编码（extra=$extra）"
        what == MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "连接或读取超时（地址不可达/服务器太慢）"
        what == MediaPlayer.MEDIA_ERROR_IO -> "读取失败：地址不可达或服务器拒绝（extra=$extra）"
        else -> "解码/网络错误（what=$what extra=$extra）"
    }

    /**
     * URL 百分号编码：DLNA 服务器常返回未转义的地址（含空格、中文），
     * 直接丢给播放器会失败。这里只编码必要的字符（保留已有 %xx 与保留符号）。
     */
    private fun encodeUrl(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            when {
                ch == ' ' -> sb.append("%20")
                ch.code in 33..126 -> sb.append(ch)
                else -> {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append('%').append(String.format("%02X", b))
                    }
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "MyUPNP"
    }
}
