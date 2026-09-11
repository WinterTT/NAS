package com.example.myupnp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max

/**
 * 本机播放 / 图片预览（第 11 课）
 * ------------------------------------------------------------------
 * 之前的播放都是"推给 DLNA 设备播"，这个页面是"在手机上自己播"：
 *   - 音乐/视频：VideoView（音频也能放，屏幕显示曲目信息 + 封面）
 *   - 图片：可双指缩放 / 拖动，双击复位
 * 点屏幕任意处关闭（视频先暂停再退出）。
 *
 * 传入：url（http 或 content://）、title、kind(image/video/audio)、artUrl 可选
 */
class LocalMediaActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var videoView: VideoView
    private lateinit var imageView: ImageView
    private lateinit var audioPanel: View
    private lateinit var localArt: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var audioTitle: TextView
    private lateinit var audioMeta: TextView
    private lateinit var loading: ProgressBar

    // 图片缩放/拖动
    private val matrix = Matrix()
    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var isImage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_media)

        root = findViewById(R.id.localRoot)
        videoView = findViewById(R.id.videoView)
        imageView = findViewById(R.id.imageView)
        audioPanel = findViewById(R.id.audioPanel)
        localArt = findViewById(R.id.localArt)
        tvTitle = findViewById(R.id.tvLocalTitle)
        audioTitle = findViewById(R.id.localAudioTitle)
        audioMeta = findViewById(R.id.localAudioMeta)
        loading = findViewById(R.id.localLoading)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "本机播放" }
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val art = intent.getStringExtra(EXTRA_ART).orEmpty()

        if (url.isBlank()) {
            Toast.makeText(this, "没有可播放的地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvTitle.text = title
        audioTitle.text = title
        audioMeta.text = if (art.isNotBlank()) "网络曲目" else ""

        when (kind) {
            KIND_IMAGE -> showImage(url)
            KIND_AUDIO -> showAudio(url, art)
            else -> showVideo(url)
        }

        // 点空白关闭（图片允许拖动，所以图片模式下先判断是否"轻点"）
        root.setOnClickListener { finish() }
    }

    // ------------------------------------------------------------------
    // 图片预览
    // ------------------------------------------------------------------

    private fun showImage(url: String) {
        isImage = true
        imageView.visibility = View.VISIBLE
        loading.visibility = View.VISIBLE
        loadBitmapAsync(url) { bmp ->
            loading.visibility = View.GONE
            if (bmp == null) {
                Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
                finish()
                return@loadBitmapAsync
            }
            imageView.setImageBitmap(bmp)
            fitCenter(bmp)
        }
        setupImageGestures()
    }

    /** 初始按屏幕居中显示（contain） */
    private fun fitCenter(bmp: Bitmap) {
        val vw = imageView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val vh = imageView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val s = minOf(vw.toFloat() / bmp.width, vh.toFloat() / bmp.height)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((vw - bmp.width * s) / 2f, (vh - bmp.height * s) / 2f)
        imageView.imageMatrix = matrix
        scale = 1f
    }

    private fun setupImageGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor.coerceIn(0.5f, 2.0f)
                    scale = (scale * factor).coerceIn(1f, 6f)
                    matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    imageView.imageMatrix = matrix
                    return true
                }
            }
        )
        val tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    imageView.post { fitCenter((imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@post) }
                    return true
                }
            }
        )
        imageView.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            tapDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == 1 && scale > 1.01f) {
                        matrix.postTranslate(e.x - lastX, e.y - lastY)
                        imageView.imageMatrix = matrix
                        lastX = e.x; lastY = e.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // 拖动距离很小 = 轻点 -> 关闭
                    if (abs(e.x - lastX) < 12 && abs(e.y - lastY) < 12 && scale <= 1.01f) {
                        finish()
                    } else {
                        lastX = e.x; lastY = e.y
                    }
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------
    // 音频 / 视频
    // ------------------------------------------------------------------

    private fun showAudio(url: String, artUrl: String) {
        audioPanel.visibility = View.VISIBLE
        videoView.visibility = View.GONE
        startPlayback(url, showVideo = false)
        if (artUrl.isNotBlank()) {
            loadBitmapAsync(artUrl) { bmp ->
                if (bmp != null) {
                    localArt.setImageBitmap(bmp)
                    localArt.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showVideo(url: String) {
        audioPanel.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        startPlayback(url, showVideo = true)
    }

    private fun startPlayback(url: String, showVideo: Boolean) {
        loading.visibility = View.VISIBLE
        runCatching {
            videoView.setVideoURI(Uri.parse(url))
            if (showVideo) {
                val controller = MediaController(this)
                controller.setAnchorView(videoView)
                videoView.setMediaController(controller)
            }
            videoView.setOnPreparedListener { mp ->
                loading.visibility = View.GONE
                mp.isLooping = false
                videoView.start()
            }
            videoView.setOnErrorListener { _, what, extra ->
                loading.visibility = View.GONE
                Log.w(TAG, "[LOCAL!] 播放失败 what=$what extra=$extra url=$url")
                Toast.makeText(this, "播放失败：可能是格式不支持或地址不可达", Toast.LENGTH_LONG).show()
                true
            }
            videoView.setOnCompletionListener {
                Toast.makeText(this, "播放结束", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            loading.visibility = View.GONE
            Toast.makeText(this, "无法开始播放：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // 图片加载（http 或 content://），后台线程
    // ------------------------------------------------------------------

    private fun loadBitmapAsync(url: String, onDone: (Bitmap?) -> Unit) {
        Thread({
            val bmp = runCatching {
                if (url.startsWith("content://")) {
                    contentResolver.openInputStream(Uri.parse(url)).use { ins ->
                        BitmapFactory.decodeStream(ins)
                    }
                } else {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        instanceFollowRedirects = true
                    }
                    try {
                        conn.inputStream.use { ins -> BitmapFactory.decodeStream(ins) }
                    } finally {
                        conn.disconnect()
                    }
                }
            }.getOrNull()
            runOnUiThread { onDone(bmp) }
        }, "local-image-load").apply { isDaemon = true }.start()
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) videoView.pause()
    }

    override fun onDestroy() {
        runCatching { videoView.stopPlayback() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MyUPNP"

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ART = "art"

        const val KIND_AUDIO = "audio"
        const val KIND_VIDEO = "video"
        const val KIND_IMAGE = "image"
    }
}
