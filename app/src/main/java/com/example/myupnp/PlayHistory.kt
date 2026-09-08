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
        val timeMs: Long = System.currentTimeMillis()
    )

    private val prefs = context.getSharedPreferences("play_history", Context.MODE_PRIVATE)

    /** 最多保留多少条 */
    val maxEntries = 30

    val size: Int get() = entries().size

    /** 读全部（新→旧） */
    fun entries(): List<Entry> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    /** 记录一首（去重：同 resUrl 挪到最前；其余顺延，超过上限丢弃尾部） */
    fun push(
        title: String,
        resUrl: String,
        artist: String = "",
        album: String = "",
        artUrl: String = ""
    ) {
        if (resUrl.isBlank()) return
        val list = entries().toMutableList()
        // 同地址的旧记录先移除（等于"又播了一次，提到最前"）
        list.removeAll { it.resUrl == resUrl && it.title == title }
        list.add(
            0,
            Entry(title = title, resUrl = resUrl, artist = artist, album = album, artUrl = artUrl)
        )
        save(list.take(maxEntries))
    }

    fun removeAt(position: Int) {
        if (position < 0) return
        val list = entries().toMutableList()
        if (position in list.indices) list.removeAt(position)
        save(list)
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
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
    }

    private fun fromJson(o: JSONObject): Entry = Entry(
        title = o.optString("t"),
        resUrl = o.optString("r"),
        artist = o.optString("a"),
        album = o.optString("l"),
        artUrl = o.optString("c"),
        timeMs = o.optLong("m")
    )

    private companion object {
        const val KEY = "entries"
    }
}
