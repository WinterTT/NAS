package com.example.myupnp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * 图片本地预览（第 13 课）
 * ------------------------------------------------------------------
 * 分工调整后的角色：本机播放只处理音乐（走 App 自己的播放页）、
 * 视频交给系统播放器 App，这个页面只负责**图片预览**：
 *   - 双指缩放、拖动查看、双击复位
 *   - 轻点关闭（拖动不会误关）
 * 支持 http（DLNA 服务器）与 content://（手机本地文件）。
 */
class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var loading: ProgressBar

    private val matrix = Matrix()
    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        val root: View = findViewById(R.id.previewRoot)
        imageView = findViewById(R.id.imageView)
        tvTitle = findViewById(R.id.previewTitle)
        loading = findViewById(R.id.previewLoading)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "没有可预览的图片地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        tvTitle.text = title
        setupGestures()
        root.setOnClickListener { finish() }

        loading.visibility = View.VISIBLE
        loadBitmapAsync(url) { bmp ->
            loading.visibility = View.GONE
            if (bmp == null) {
                Toast.makeText(this, "图片加载失败（地址不可达或格式不支持）", Toast.LENGTH_LONG).show()
                finish()
                return@loadBitmapAsync
            }
            imageView.setImageBitmap(bmp)
            imageView.post { fitCenter(bmp) }
        }
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

    private fun setupGestures() {
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
                    (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
                        fitCenter(it)
                    }
                    return true
                }
            }
        )
        imageView.setOnTouchListener { _, e ->
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
        }, "image-preview-load").apply { isDaemon = true }.start()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }
}
