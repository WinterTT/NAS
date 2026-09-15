package com.havencast.remote

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.havencast.remote.model.MediaItem
import java.util.concurrent.ExecutorService

/**
 * 本机音乐播放器（第 13 课：本机播放只处理音频，复用 App 的播放页）
 * ------------------------------------------------------------------
 * 用 AndroidX Media3 ExoPlayer 播音频（http 或 content://）；视频交给系统播放器 App。
 * 状态通过 [Listener] 回到主线程，由 ViewModel 合成到 UiState，
 * 于是**同一个播放页/控制条**既能控制 DLNA 设备，也能控制本机播放。
 *
 * 细节：
 *   - URL 会做百分号编码（DLNA 服务器常返回带空格/中文的未转义地址）
 *   - 音量作用于手机媒体音量（AudioManager.STREAM_MUSIC）
 *   - ExoPlayer 统一处理播放列表、缓冲与音频焦点；视频交系统播放器，避免扩大 UI 范围
 *   - 失败时给出可读原因（网络/容器/解码器错误码）
 */
class LocalPlayer(
    private val mainHandler: Handler,
    private val fetchExecutor: ExecutorService,
    private val listener: Listener
) {

    interface Listener {
        /** 状态变化（播放/暂停/进度/时长） */
        fun onLocalStateChanged()

        /** 一首播完（用于连播下一首） */
        fun onLocalCompleted()

        /** 出错（已给出可读原因） */
        fun onLocalError(message: String)

        /** 本地文件的内嵌封面已读取；null 表示没有或读取失败 */
        fun onLocalArtworkChanged()
    }

    private var player: ExoPlayer? = null
    private var appContext: Context? = null
    private var artworkRequestId = 0L

    /** 本地文件标签中的内嵌封面；仅供 ViewModel 写入 UiState，不直接碰 View。 */
    var artworkBytes: ByteArray? = null
        private set

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> listener.onLocalStateChanged()
                Player.STATE_ENDED -> {
                    val item = current ?: return
                    Log.i(TAG, "[LOCAL] 播放完成: ${item.title}")
                    listener.onLocalCompleted()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener.onLocalStateChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            val reason = decodeError(error)
            Log.w(TAG, "[LOCAL!] 播放失败 code=${error.errorCodeName}: ${error.message}", error)
            listener.onLocalError("本机播放失败：$reason")
        }
    }

    /** 当前本机播放的曲目（null = 未在本机播放） */
    var current: MediaItem? = null
        private set

    val isActive: Boolean get() = current != null

    fun play(context: Context, item: MediaItem) {
        appContext = context.applicationContext
        stopInternal()
        current = item
        requestEmbeddedArtwork(context.applicationContext, item)
        try {
            val url = item.resUrl
            val uri = Uri.parse(if (url.startsWith("content://")) url else encodeUrl(url))
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            player = ExoPlayer.Builder(context.applicationContext).build().apply {
                setAudioAttributes(audioAttributes, true)
                addListener(playerListener)
                setMediaItem(Media3Item.fromUri(uri))
                prepare()
                play()
            }
            listener.onLocalStateChanged()
        } catch (e: Exception) {
            Log.w(TAG, "[LOCAL!] 无法开始播放: ${e.message}")
            stopInternal()
            listener.onLocalError("无法开始播放：${e.message}")
        }
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun toggle() {
        val exoPlayer = player ?: return
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        listener.onLocalStateChanged()
    }

    fun stop() {
        val item = current
        stopInternal()
        current = null
        artworkRequestId++
        artworkBytes = null
        Log.i(TAG, "[LOCAL] 停止本机播放: ${item?.title ?: "-"}")
        listener.onLocalStateChanged()
    }

    fun positionSec(): Long =
        player?.currentPosition?.takeIf { it >= 0 }?.div(1000L) ?: 0L

    fun durationSec(): Long =
        player?.duration?.takeIf { it != C.TIME_UNSET && it >= 0 }?.div(1000L) ?: 0L

    fun seekTo(sec: Long) {
        player?.seekTo(sec.coerceAtLeast(0) * 1000L)
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
        artworkRequestId++
        artworkBytes = null
    }

    private fun stopInternal() {
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    /**
     * 只对手机本地 content:// 文件读取 ID3/FLAC 等标签中的封面；网络曲目继续用服务器 artUrl。
     * MediaMetadataRetriever 可能阻塞，必须放 VM 注入的后台线程。
     */
    private fun requestEmbeddedArtwork(context: Context, item: MediaItem) {
        val requestId = ++artworkRequestId
        artworkBytes = null
        listener.onLocalArtworkChanged()
        val uri = item.resUrl.takeIf { it.startsWith("content://") } ?: return
        fetchExecutor.execute {
            val bytes = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(uri))
                    retriever.embeddedPicture?.takeIf { it.size <= MAX_ART_BYTES }
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            mainHandler.post {
                if (requestId != artworkRequestId || current != item) return@post
                artworkBytes = bytes
                listener.onLocalArtworkChanged()
            }
        }
    }

    /** Media3 错误码 → 人话 */
    private fun decodeError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连接或读取超时（地址不可达/服务器太慢）"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "读取失败：地址不可达或服务器拒绝"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "手机不支持这个音频格式/编码"
        else -> "解码或网络错误（${error.errorCodeName}）"
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
        const val TAG = "HavenCast"
        const val MAX_ART_BYTES = 4 * 1024 * 1024
    }
}
