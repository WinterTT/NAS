package com.example.myupnp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放历史（第 7 课 D）
 * ------------------------------------------------------------------
 * 自动记录"最近播放"（持久化到 SharedPreferences，JSON 存一组曲目）。
 * 记录在"推送成功"后由 MainViewModel.noteHistoryPlayed() 写入。
 * 纯存储，不碰 UI；供历史列表弹窗读取与重播。
 */
class PlayHistory(context: Context) {

    data class Entry(
        val title: String,
        val resUrl: String,
        val artist: String = "",
        val album: String = "",
        val artUrl: String = "",
        val timeMs: Long = System.currentTimeMillis(),
        /** 第 9 课：所属网络（net:192.168.1）；空 = 旧数据/未知 */
        val net: String = ""
    )

    private val prefs = context.getSharedPreferences("play_history", Context.MODE_PRIVATE)

    /** 最多保留多少条 */
    val maxEntries = 30

    /** 记录条数（net=null 表示不过滤） */
    fun size(net: String? = null): Int = entries(net).size

    /**
     * 读记录（新→旧）。
     * @param net 传当前网络键时只返回该网络的记录（旧的无网络标记数据仍显示）
     */
    fun entries(net: String? = null): List<Entry> {
        val all = entriesAll()
        if (net == null) return all
        return all.filter { it.net.isEmpty() || it.net == net }
    }

    private fun entriesAll(): List<Entry> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 记录一首（去重：同 resUrl 挪到最前；其余顺延，超过上限丢弃尾部）。
     * @param net 当前网络键，换网络后各网络只显示自己的记录
     */
    fun push(
        title: String,
        resUrl: String,
        artist: String = "",
        album: String = "",
        artUrl: String = "",
        net: String = ""
    ) {
        if (resUrl.isBlank()) return
        val list = entriesAll().toMutableList()
        // 同地址的旧记录先移除（等于"又播了一次，提到最前"）
        list.removeAll { it.resUrl == resUrl && it.title == title }
        list.add(
            0,
            Entry(
                title = title, resUrl = resUrl, artist = artist, album = album,
                artUrl = artUrl, net = net
            )
        )
        save(list.take(maxEntries))
    }

    /** 删除当前网络视图下的第 position 条 */
    fun removeAt(position: Int, net: String? = null) {
        if (position < 0) return
        val target = entries(net).getOrNull(position) ?: return
        val list = entriesAll().toMutableList()
        list.removeAll { it.resUrl == target.resUrl && it.title == target.title }
        save(list)
    }

    /** 清空：net=null 清全部；否则只清该网络的记录 */
    fun clear(net: String? = null) {
        if (net == null) {
            prefs.edit().remove(KEY).apply()
            return
        }
        save(entriesAll().filterNot { it.net == net })
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) arr.put(toJson(e))
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(e: Entry): JSONObject = JSONObject().apply {
        put("t", e.title)
        put("r", e.resUrl)
        put("a", e.artist)
        put("l", e.album)
        put("c", e.artUrl)
        put("m", e.timeMs)
        put("n", e.net)
    }

    private fun fromJson(o: JSONObject): Entry = Entry(
        title = o.optString("t"),
        resUrl = o.optString("r"),
        artist = o.optString("a"),
        album = o.optString("l"),
        artUrl = o.optString("c"),
        timeMs = o.optLong("m"),
        net = o.optString("n")
    )

    private companion object {
        const val KEY = "entries"
    }
}
