package com.example.myupnp

import com.example.myupnp.model.MediaItem

/**
 * 播放队列（第 7 课 B：点歌排队 / 播完自动连播）
 * ------------------------------------------------------------------
 * 三段式结构，纯逻辑、不碰 UI/网络：
 *   - pending  接下来要播的（队首 = 下一首，自动连播从这里取）
 *   - current  当前这首（由"播放/连播/切歌"推进）
 *   - history  播完的（栈顶 = 最近一首，"上一首"从这里回放）
 *
 * 队列操作与网络解耦：先 [nextUp]/[previousUp]/[pendingAt] 预览目标，
 * 推送成功后用 [commitNext]/[commitPrevious]/[commitPlayNow] 记账；
 * 失败就不记账，避免把播不了的歌吞掉。
 *
 * 未来的收费口子：队列功能可绑定 FeatureId.PLAYBACK_QUEUE
 * （见 core/FeatureId.kt），现在免费全开。
 */
class PlaybackQueue {

    private val pending = ArrayList<MediaItem>() // 队首 = 下一首
    private val history = ArrayList<MediaItem>() // 末尾 = 最近播完
    var current: MediaItem? = null
        private set

    val pendingCount: Int get() = pending.size
    val historyCount: Int get() = history.size

    /** 队列是否被用过（避免对从不排队的人喊"队列播完"） */
    val hasActivity: Boolean
        get() = pending.isNotEmpty() || current != null || history.isNotEmpty()

    /** 下一首（自动连播 / 下一首按钮的目标） */
    val nextUp: MediaItem? get() = pending.firstOrNull()

    /** 上一首（最近播完的，可回放） */
    val previousUp: MediaItem? get() = history.lastOrNull()

    /** 排到队尾；不打断当前播放 */
    fun enqueue(item: MediaItem) {
        pending.add(item)
    }

    /** 取第 i 首待播（预览；点队列列表"立即切到这首"用） */
    fun pendingAt(index: Int): MediaItem? =
        if (index in pending.indices) pending[index] else null

    /** 推送成功后的记账：确认队首就是 [item]，消费它并归档当前 */
    fun commitNext(item: MediaItem): Boolean {
        val head = pending.firstOrNull() ?: return false
        if (!same(head, item)) return false
        pending.removeAt(0)
        archiveCurrent()
        current = item
        return true
    }

    /** 推送成功后的记账：把 [item] 从历史取回重播，当前曲放回队首（播完再接上） */
    fun commitPrevious(item: MediaItem): Boolean {
        val idx = history.indexOfLast { same(it, item) }
        if (idx < 0) return false
        history.removeAt(idx)
        current?.let { pending.add(0, it) }
        current = item
        return true
    }

    /** 推送成功后的记账：作为"当前这首"开始播（若它还在队里则移出） */
    fun commitPlayNow(item: MediaItem) {
        pending.removeAll { same(it, item) }
        archiveCurrent()
        current = item
    }

    /** 把当前这首归档进历史（只留最近 50 首） */
    private fun archiveCurrent() {
        val c = current ?: return
        if (history.isEmpty() || !same(history.last(), c)) history.add(c)
        while (history.size > 50) history.removeAt(0)
    }

    fun clear() {
        pending.clear()
        history.clear()
        current = null
    }

    // ------------------------------------------------------------------
    // 队列管理增强（第 7 课 A）
    // ------------------------------------------------------------------

    /** 插队到队首（"下一首播放"）：同曲先移除再放到最前 */
    fun playNext(item: MediaItem) {
        pending.removeAll { same(it, item) }
        pending.add(0, item)
    }

    /** 整批插到队首（"整张专辑 → 下一首播放"）：保持传入顺序，重复的先移除 */
    fun playNextAll(items: List<MediaItem>) {
        if (items.isEmpty()) return
        pending.removeAll { p -> items.any { same(p, it) } }
        pending.addAll(0, items)
    }

    /** 把待播中的第 index 首移到队首（队列里的"移到下一首"） */
    fun movePendingToFront(index: Int) {
        if (index !in pending.indices) return
        val item = pending.removeAt(index)
        pending.add(0, item)
    }

    /** 删除待播中的第 index 首（不影响当前在播的） */
    fun removePendingAt(index: Int) {
        if (index in pending.indices) pending.removeAt(index)
    }

    /** 把待播中的第 index 首上移(delta=-1)/下移(delta=1) */
    fun movePending(index: Int, delta: Int) {
        if (index !in pending.indices) return
        val target = index + delta
        if (target !in pending.indices) return
        val item = pending.removeAt(index)
        pending.add(target, item)
    }

    /** 重启后恢复"待播列表"（当前曲/历史属于本次会话，不恢复） */
    fun restorePending(items: List<MediaItem>) {
        pending.clear()
        pending.addAll(items)
    }

    fun snapshotPending(): List<MediaItem> = pending.toList()

    /** 同一首歌：用 id + 播放地址判断（不同次 Browse 拿到的对象也认） */
    private fun same(a: MediaItem, b: MediaItem): Boolean =
        a.id == b.id && a.resUrl == b.resUrl
}
