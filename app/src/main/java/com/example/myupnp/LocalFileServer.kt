package com.example.myupnp

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地文件 HTTP 服务（第 7 课 B：把手机里的媒体推给音箱/电视播）
 * ------------------------------------------------------------------
 * 原理：DLNA 渲染器（音箱/电视）拿到的是 http://手机IP:端口/xxx 的地址，
 * 由它自己去拉流。所以需要在手机上临时起一个 HTTP 服务，把选中的本地文件
 * 吐给请求方。支持 Range（渲染器 Seek/拖进度会带），端口随机。
 *
 * 单文件服务：一次 serve 当前选中文件；Android 渲染器主要按给定 URL 拉一次。
 * 由 MainViewModel 持有/启停，线程全部 daemon。
 */
class LocalFileServer(private val app: Application) {

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** 当前要提供的文件（每次推送本地文件时更新） */
    @Volatile
    private var currentUri: Uri? = null
    @Volatile
    private var currentName: String = "media.bin"

    val port: Int get() = server?.localPort ?: 0

    fun isRunning(): Boolean = running.get()

    /** 记录本次要服务的文件（名称用于推断 Content-Type） */
    fun setCurrent(uri: Uri, name: String) {
        currentUri = uri
        currentName = name
    }

    /** 启动 HTTP 服务（已在跑则直接返回） */
    fun start(): Boolean {
        if (running.get()) return true
        synchronized(this) {
            if (running.get()) return true
            runCatching {
                server = ServerSocket(0).also {
                    running.set(true)
                    acceptThread = Thread({ acceptLoop() }, "local-file-server").apply {
                        isDaemon = true
                    }.apply { start() }
                    Log.i(TAG, "[LOCAL-FILE] HTTP 服务已启动 port=${it.localPort}")
                }
            }.onFailure {
                running.set(false)
                server = null
                Log.w(TAG, "[LOCAL-FILE!] 启动失败: ${it.message}")
            }
        }
        return running.get()
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        Log.i(TAG, "[LOCAL-FILE] HTTP 服务已停止")
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = runCatching { server?.accept() }.getOrNull()
            if (client == null) {
                if (running.get()) {
                    runCatching { Thread.sleep(200) }
                }
                continue
            }
            Thread({ handle(client) }, "local-file-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            try {
                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1)
                )
                val first = reader.readLine() ?: return
                val parts = first.trim().split(" ")
                if (parts.size < 2 || parts[0].uppercase() != "GET") return
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals("Range", ignoreCase = true)) {
                        range = line.substring(idx + 1).trim()
                    }
                }
                serve(s, range)
            } catch (e: Exception) {
                Log.w(TAG, "[LOCAL-FILE!] 连接处理异常: ${e.message}")
            }
        }
    }

    private fun serve(s: Socket, rangeHeader: String?) {
        val uri = currentUri
        val out = s.getOutputStream()
        if (uri == null) {
            respondError(out, "no file")
            return
        }
        val resolver: Context = app
        try {
            resolver.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val total = afd.length // -1/0 = 未知（非普通文件时 -1）
                val type = guessContentType(currentName)

                var start = 0L
                var end = if (total > 0) total - 1 else -1L
                var partial = false
                if (rangeHeader != null && total > 0) {
                    val m = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader)
                    if (m != null) {
                        val from = m.groupValues[1].toLongOrNull()
                        val to = m.groupValues[2].toLongOrNull()
                        if (from != null && from < total) {
                            start = from
                            if (to != null) end = minOf(to, total - 1) else end = total - 1
                            partial = true
                        }
                    }
                }

                val sb = StringBuilder()
                sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                sb.append("Content-Type: $type\r\n")
                if (total > 0) {
                    if (partial) {
                        sb.append("Content-Range: bytes $start-$end/$total\r\n")
                    }
                    sb.append("Content-Length: ${if (end >= 0) end - start + 1 else 0}\r\n")
                }
                sb.append("Accept-Ranges: bytes\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                out.flush()

                // 从 start 开始流（普通文件用 fd，可定位支持 Range）
                FileInputStream(afd.fileDescriptor).use { fis ->
                    if (start > 0) {
                        fis.channel.position(start)
                    }
                    val buf = ByteArray(64 * 1024)
                    var remaining = if (end >= 0) (end - start + 1) else Long.MAX_VALUE
                    while (remaining > 0) {
                        val want = if (remaining > buf.size.toLong()) buf.size.toLong() else remaining
                        val n = fis.read(buf, 0, want.toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                    out.flush()
                }
            } ?: respondError(out, "cannot open file")
        } catch (e: Exception) {
            Log.w(TAG, "[LOCAL-FILE!] 响应失败: ${e.message}")
            respondError(out, "error")
        }
    }

    private fun respondError(out: java.io.OutputStream, msg: String) {
        runCatching {
            val body = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(body.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    /** 按扩展名猜 Content-Type（DIDL/渲染器一般靠它识别） */
    private fun guessContentType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "wma" -> "audio/x-ms-wma"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    private companion object {
        const val TAG = "MyUPNP"
    }
}
