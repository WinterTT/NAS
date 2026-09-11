package com.example.myupnp

import android.util.Log
import com.example.myupnp.dlna.ContentDirectoryClient
import com.example.myupnp.model.MediaContainer
import com.example.myupnp.model.MediaItem
import com.example.myupnp.model.UpnpService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 曲库索引器（第 8 课：索引）
 * ------------------------------------------------------------------
 * 广度优先把一台 MediaServer 的曲库爬一遍，把条目写进 [MediaIndexStore]：
 *   - 每个目录一次 Browse（沿用现有 ContentDirectoryClient）
 *   - 目录扫描状态入库：中断/退出后可**续扫**，不用从头再来
 *   - 可随时 [stop]，进度通过 [Listener] 回调（调用方负责切主线程）
 *
 * 单线程执行（daemon），不占用 controlExecutor，避免影响用户操作。
 */
class LibraryIndexer(
    private val store: MediaIndexStore,
    private val listener: Listener
) {

    interface Listener {
        /** 进度：已扫目录数 / 已收录曲目数 / 跳过的重复数 / 当前目录名 */
        fun onProgress(scannedContainers: Int, indexedItems: Int, skippedDuplicates: Int, currentPath: String)

        /** 结束：reason = done | stopped | error */
        fun onFinished(serverUdn: String, indexedItems: Int, reason: String)
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "library-indexer").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /**
     * 开始（或继续）索引一台服务器。
     * @param serverUdn 服务器稳定 ID（UDN），作为索引归属
     */
    fun start(serverUdn: String, cds: UpnpService) {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "[INDEX] 已在运行，忽略本次请求")
            return
        }
        executor.execute { walk(serverUdn, cds) }
    }

    fun stop() {
        if (running.get()) {
            Log.i(TAG, "[INDEX] 收到停止请求")
            running.set(false)
        }
    }

    private fun walk(serverUdn: String, cds: UpnpService) {
        val now = System.currentTimeMillis()
        // 根目录入队（若已扫过会因 CONFLICT_IGNORE 保留状态 → 支持续扫）
        store.putContainer(serverUdn, ROOT_ID, null, "根目录", now)

        var scanned = 0
        var items = store.stats().itemCount
        var skipped = 0

        try {
            while (running.get()) {
                val next = store.nextUnscannedContainer(serverUdn)
                if (next == null) {
                    Log.i(TAG, "[INDEX] 扫描完成：目录 $scanned 个，跳过重复 $skipped 首")
                    listener.onFinished(serverUdn, items, "done")
                    return
                }
                val (objectId, title) = next
                val out = ContentDirectoryClient.browse(cds, objectId)
                if (!out.ok) {
                    // 单个目录失败不中断整体：标记跳过，继续扫别的
                    Log.w(TAG, "[INDEX!] Browse 失败 ${title}($objectId): ${out.error}")
                    store.markContainerScanned(serverUdn, objectId, System.currentTimeMillis())
                    continue
                }
                for (obj in out.objects) {
                    when (obj) {
                        is MediaContainer -> store.putContainer(
                            serverUdn, obj.id, objectId, obj.title, System.currentTimeMillis()
                        )
                        is MediaItem -> {
                            if (obj.resUrl.isNotBlank()) {
                                // 命中任一去重键（同文件多 URL / 同标签）时返回 false
                                val inserted = store.putItem(
                                    serverUdn, objectId, obj, System.currentTimeMillis()
                                )
                                if (inserted) items++ else skipped++
                            }
                        }
                    }
                }
                store.markContainerScanned(serverUdn, objectId, System.currentTimeMillis())
                scanned++
                listener.onProgress(scanned, items, skipped, title.ifEmpty { objectId })
            }
            listener.onFinished(serverUdn, items, "stopped")
        } catch (e: Exception) {
            Log.w(TAG, "[INDEX!] 扫描异常: ${e.message}")
            listener.onFinished(serverUdn, items, "error")
        } finally {
            running.set(false)
        }
    }

    private companion object {
        const val TAG = "MyUPNP"
        const val ROOT_ID = ContentDirectoryClient.ROOT_OBJECT_ID
    }
}
