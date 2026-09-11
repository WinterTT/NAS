package com.example.myupnp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.myupnp.model.MediaItem

/**
 * 本地媒体索引（第 8 课：搜索与索引）
 * ------------------------------------------------------------------
 * 把曲库"爬"一遍后把条目的元数据存进 SQLite，之后：
 *   - 秒级本地搜索（标题 / 歌手 / 专辑，用 LIKE 在这个量级足够快）
 *   - 为后续功能打基础：整库随机播放、按歌手/专辑浏览、服务器整理建议
 *
 * 两张表：
 *   items      曲目条目（res_url <> '' 的才算可播放）
 *   containers 目录扫描状态（scanned=0 表示还没扫，用于断点续扫）
 *
 * 线程约定：SQLiteOpenHelper 自身线程安全，索引线程与主线程都可直接调用。
 */
class MediaIndexStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    /** 索引里的一条曲目 */
    data class IndexEntry(
        val serverUdn: String,
        val objectId: String,
        val title: String,
        val artist: String,
        val album: String,
        val upnpClass: String,
        val resUrl: String,
        val mime: String,
        val artUrl: String,
        /** 归一化 URL 去重键 */
        val dedupeKey: String = "",
        /** 标题+歌手+专辑 去重键 */
        val trackKey: String = "",
        /** 第 10 课：所属顶层分类（音乐 / 视频 / 图片…） */
        val topId: String = "",
        val topTitle: String = ""
    ) {
        fun toMediaItem(): MediaItem = MediaItem(
            id = objectId,
            title = title.ifEmpty { "（未命名）" },
            upnpClass = upnpClass,
            resUrl = resUrl,
            mime = mime,
            artist = artist,
            album = album,
            artUrl = artUrl
        )

        /** 搜索时用来判断"是不是同一首歌" */
        fun identityKey(): String =
            trackKey.ifEmpty { dedupeKey.ifEmpty { resUrl } }
    }

    /** 索引概览 */
    data class Stats(val itemCount: Int, val serverCount: Int, val pendingContainers: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE items(
              server_udn TEXT NOT NULL,
              object_id  TEXT NOT NULL,
              parent_id  TEXT,
              top_id     TEXT,
              top_title  TEXT,
              title      TEXT,
              artist     TEXT,
              album      TEXT,
              upnp_class TEXT,
              res_url    TEXT,
              mime       TEXT,
              art_url    TEXT,
              size_bytes INTEGER DEFAULT 0,
              dur_sec    INTEGER DEFAULT 0,
              dedupe_key TEXT,
              file_key   TEXT,
              track_key  TEXT,
              updated_at INTEGER,
              PRIMARY KEY(server_udn, object_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_items_title ON items(title)")
        db.execSQL("CREATE INDEX idx_items_top ON items(server_udn, top_id)")
        // 三重去重键（空键不参与唯一约束，避免把"没标签的条目"全判成同一个）：
        //   dedupe_key 归一化 URL（去 query/fragment、小写）
        //   file_key   文件大小+时长（同一文件必然一致，跟 URL/标签无关）← 最硬的判据
        //   track_key  标题|歌手|专辑
        db.execSQL("CREATE UNIQUE INDEX idx_items_res ON items(server_udn, dedupe_key) WHERE dedupe_key <> ''")
        db.execSQL("CREATE UNIQUE INDEX idx_items_file ON items(server_udn, file_key) WHERE file_key <> ''")
        db.execSQL("CREATE UNIQUE INDEX idx_items_track ON items(server_udn, track_key) WHERE track_key <> ''")
        db.execSQL(
            """
            CREATE TABLE containers(
              server_udn TEXT NOT NULL,
              object_id  TEXT NOT NULL,
              parent_id  TEXT,
              title      TEXT,
              top_id     TEXT,
              top_title  TEXT,
              scanned    INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER,
              PRIMARY KEY(server_udn, object_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v4：新增"顶层分类"字段（音乐/视频/图片…）。旧索引没有这些信息，
        // 直接重建表结构 —— 索引可再生，用户重新建一次索引即可。
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS items")
            db.execSQL("DROP TABLE IF EXISTS containers")
            onCreate(db)
        }
    }

    // ------------------------------------------------------------------
    // 写入（索引线程调用）
    // ------------------------------------------------------------------

    /**
     * 写入一条曲目。命中任一去重键（URL 归一化 / 文件大小+时长 / 标题+歌手+专辑）
     * 时会被忽略，返回 false —— 这就是"同一首歌多视图多 URL"不再重复入库的关键。
     */
    /** 顶层分类行（音乐 / 视频 / 图片…） */
    data class CategoryRow(
        val topId: String,
        val title: String,
        val itemCount: Int,
        /** 该分类下占多数的媒体类型：音乐 / 视频 / 图片 / 其他 */
        val kind: String
    )

    fun putItem(
        serverUdn: String,
        parentId: String,
        topId: String,
        topTitle: String,
        item: MediaItem,
        now: Long
    ): Boolean {
        val values = ContentValues().apply {
            put("server_udn", serverUdn)
            put("object_id", item.id)
            put("parent_id", parentId)
            put("top_id", topId)
            put("top_title", topTitle)
            put("title", item.title)
            put("artist", item.artist)
            put("album", item.album)
            put("upnp_class", item.upnpClass)
            put("res_url", item.resUrl)
            put("mime", item.mime)
            put("art_url", item.artUrl)
            put("size_bytes", item.sizeBytes)
            put("dur_sec", item.durationSec)
            put("dedupe_key", normalizeUrl(item.resUrl))
            put("file_key", fileKeyOf(item))
            put("track_key", trackKeyOf(item))
            put("updated_at", now)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "items", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        return rowId != -1L
    }

    /** URL 归一化：去掉 fragment / query / path 参数并小写，用于识别"同一文件的不同写法" */
    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return t.substringBefore('#')
            .substringBefore('?')
            .substringBefore(';')
            .lowercase()
    }

    /** 文件键：大小+时长（都拿得到才有意义）；同一文件被不同 URL/标签暴露时仍一致 */
    private fun fileKeyOf(item: MediaItem): String {
        if (item.sizeBytes <= 0) return ""
        return "${item.sizeBytes}|${item.durationSec}"
    }

    /** 曲目键：标题|歌手|专辑（归一化：小写、压缩空白） */
    private fun trackKeyOf(item: MediaItem): String {
        fun n(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        val t = n(item.title)
        val a = n(item.artist)
        val al = n(item.album)
        if (t.isEmpty() && a.isEmpty() && al.isEmpty()) return ""
        return "$t|$a|$al"
    }

    /** 目录扫描项（含所属顶层分类，续扫时也能知道分类） */
    data class ContainerRow(
        val objectId: String,
        val title: String,
        val topId: String,
        val topTitle: String
    )

    fun putContainer(
        serverUdn: String,
        objectId: String,
        parentId: String?,
        title: String,
        topId: String,
        topTitle: String,
        now: Long
    ) {
        val values = ContentValues().apply {
            put("server_udn", serverUdn)
            put("object_id", objectId)
            put("parent_id", parentId)
            put("title", title)
            put("top_id", topId)
            put("top_title", topTitle)
            put("scanned", 0)
            put("updated_at", now)
        }
        // 已存在的目录不覆盖 scanned 状态（避免重复入队）
        writableDatabase.insertWithOnConflict(
            "containers", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** 取一个还没扫的目录（断点续扫）；没有则返回 null */
    fun nextUnscannedContainer(serverUdn: String): ContainerRow? {
        readableDatabase.rawQuery(
            """
            SELECT object_id, title, top_id, top_title
            FROM containers WHERE server_udn=? AND scanned=0 LIMIT 1
            """.trimIndent(),
            arrayOf(serverUdn)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val objectId: String = c.getString(0)
            val title: String = c.getString(1) ?: ""
            val topId: String = c.getString(2) ?: objectId
            val topTitle: String = c.getString(3) ?: title
            return ContainerRow(objectId, title, topId, topTitle)
        }
    }

    fun markContainerScanned(serverUdn: String, objectId: String, now: Long) {
        writableDatabase.execSQL(
            "UPDATE containers SET scanned=1, updated_at=? WHERE server_udn=? AND object_id=?",
            arrayOf<Any?>(now, serverUdn, objectId)
        )
    }

    /** 重扫某台服务器：清掉它的目录状态（曲目保留，重扫时覆盖更新） */
    fun resetServer(serverUdn: String) {
        writableDatabase.execSQL("DELETE FROM containers WHERE server_udn=?", arrayOf(serverUdn))
    }

    fun deleteServer(serverUdn: String) {
        writableDatabase.execSQL("DELETE FROM items WHERE server_udn=?", arrayOf(serverUdn))
        writableDatabase.execSQL("DELETE FROM containers WHERE server_udn=?", arrayOf(serverUdn))
    }

    // ------------------------------------------------------------------
    // 查询（搜索 UI 调用）
    // ------------------------------------------------------------------

    /** 本地搜索：标题 / 歌手 / 专辑 模糊匹配（只返回可播放条目；跨服务器按 res_url 去重） */
    fun search(keyword: String, limit: Int = 200): List<IndexEntry> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val like = "%$kw%"
        // 用 identityKey（曲目键优先）去重：同一首歌被多台服务器/多个视图暴露时只留一条
        val unique = LinkedHashMap<String, IndexEntry>()
        readableDatabase.rawQuery(
            """
            SELECT server_udn, object_id, title, artist, album, upnp_class, res_url, mime, art_url,
                   dedupe_key, track_key
            FROM items
            WHERE res_url <> '' AND (title LIKE ? OR artist LIKE ? OR album LIKE ?)
            ORDER BY title
            LIMIT ?
            """.trimIndent(),
            arrayOf(like, like, like, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val entry = IndexEntry(
                    serverUdn = c.getString(0),
                    objectId = c.getString(1),
                    title = c.getString(2) ?: "",
                    artist = c.getString(3) ?: "",
                    album = c.getString(4) ?: "",
                    upnpClass = c.getString(5) ?: "",
                    resUrl = c.getString(6) ?: "",
                    mime = c.getString(7) ?: "",
                    artUrl = c.getString(8) ?: "",
                    dedupeKey = c.getString(9) ?: "",
                    trackKey = c.getString(10) ?: ""
                )
                unique.putIfAbsent(entry.identityKey(), entry)
            }
        }
        return unique.values.toList().take(limit)
    }

    fun stats(): Stats {
        var items = 0
        var servers = 0
        var pending = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE res_url <> ''", null).use {
            if (it.moveToFirst()) items = it.getInt(0)
        }
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT server_udn) FROM items", null).use {
            if (it.moveToFirst()) servers = it.getInt(0)
        }
        readableDatabase.rawQuery("SELECT COUNT(*) FROM containers WHERE scanned=0", null).use {
            if (it.moveToFirst()) pending = it.getInt(0)
        }
        return Stats(items, servers, pending)
    }

    // ------------------------------------------------------------------
    // 分类浏览（专辑 / 歌手 / 歌曲）
    // ------------------------------------------------------------------

    /** 专辑行 */
    data class AlbumRow(val album: String, val artist: String, val songCount: Int)

    /** 歌手行 */
    data class ArtistRow(val artist: String, val songCount: Int, val albumCount: Int)

    fun countForServer(serverUdn: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM items WHERE server_udn=? AND res_url <> ''",
            arrayOf(serverUdn)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 顶层分类（音乐 / 视频 / 图片…）：按条数排序，附带"主要媒体类型" */
    fun categories(serverUdn: String): List<CategoryRow> {
        val out = ArrayList<CategoryRow>()
        readableDatabase.rawQuery(
            """
            SELECT top_id, top_title,
                   COUNT(*) AS c,
                   SUM(CASE WHEN upnp_class LIKE '%audio%' OR mime LIKE 'audio%' THEN 1 ELSE 0 END) AS aud,
                   SUM(CASE WHEN upnp_class LIKE '%video%' OR mime LIKE 'video%' THEN 1 ELSE 0 END) AS vid,
                   SUM(CASE WHEN upnp_class LIKE '%image%' OR mime LIKE 'image%' THEN 1 ELSE 0 END) AS img
            FROM items
            WHERE server_udn=? AND res_url <> ''
            GROUP BY top_id
            ORDER BY c DESC
            """.trimIndent(),
            arrayOf(serverUdn)
        ).use { c ->
            while (c.moveToNext()) {
                val count = c.getInt(2)
                val aud = c.getInt(3)
                val vid = c.getInt(4)
                val img = c.getInt(5)
                val kind = when {
                    aud >= vid && aud >= img && aud > 0 -> "音乐"
                    vid >= aud && vid >= img && vid > 0 -> "视频"
                    img >= aud && img >= vid && img > 0 -> "图片"
                    else -> "其他"
                }
                out.add(
                    CategoryRow(
                        topId = c.getString(0) ?: "",
                        title = c.getString(1) ?: "未分类",
                        itemCount = count,
                        kind = kind
                    )
                )
            }
        }
        return out
    }

    /** 按专辑分组（同一专辑名可能出现不同歌手，这里取其中一个歌手做提示） */
    fun albums(serverUdn: String, topId: String? = null): List<AlbumRow> {
        val out = ArrayList<AlbumRow>()
        val top = topId.orEmpty()
        val topClause = if (top.isEmpty()) "" else " AND top_id = ?"
        val args = if (top.isEmpty()) arrayOf(serverUdn) else arrayOf(serverUdn, top)
        readableDatabase.rawQuery(
            """
            SELECT album, MAX(artist) AS a, COUNT(*) AS c
            FROM items
            WHERE server_udn=? AND res_url <> '' AND album <> ''$topClause
            GROUP BY album
            ORDER BY album
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) {
                out.add(AlbumRow(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2)))
            }
        }
        return out
    }

    /** 按歌手分组 */
    fun artists(serverUdn: String, topId: String? = null): List<ArtistRow> {
        val out = ArrayList<ArtistRow>()
        val top = topId.orEmpty()
        val topClause = if (top.isEmpty()) "" else " AND top_id = ?"
        val args = if (top.isEmpty()) arrayOf(serverUdn) else arrayOf(serverUdn, top)
        readableDatabase.rawQuery(
            """
            SELECT artist, COUNT(*) AS c, COUNT(DISTINCT album) AS al
            FROM items
            WHERE server_udn=? AND res_url <> '' AND artist <> ''$topClause
            GROUP BY artist
            ORDER BY artist
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ArtistRow(c.getString(0) ?: "", c.getInt(1), c.getInt(2)))
            }
        }
        return out
    }

    /** 全部曲目（按标题排序），可按顶层分类过滤 */
    fun songs(serverUdn: String, topId: String? = null, limit: Int = 5000): List<IndexEntry> {
        val top = topId.orEmpty()
        val topClause = if (top.isEmpty()) "" else " AND top_id = ?"
        val args = if (top.isEmpty()) arrayOf(serverUdn, limit.toString())
        else arrayOf(serverUdn, top, limit.toString())
        return query(
            "WHERE server_udn=? AND res_url <> ''$topClause ORDER BY title LIMIT ?",
            args
        )
    }

    fun songsByAlbum(serverUdn: String, album: String, topId: String? = null): List<IndexEntry> {
        val top = topId.orEmpty()
        val topClause = if (top.isEmpty()) "" else " AND top_id = ?"
        val args = if (top.isEmpty()) arrayOf(serverUdn, album)
        else arrayOf(serverUdn, album, top)
        return query(
            "WHERE server_udn=? AND res_url <> '' AND album=?$topClause ORDER BY title",
            args
        )
    }

    fun songsByArtist(serverUdn: String, artist: String, topId: String? = null): List<IndexEntry> {
        val top = topId.orEmpty()
        val topClause = if (top.isEmpty()) "" else " AND top_id = ?"
        val args = if (top.isEmpty()) arrayOf(serverUdn, artist)
        else arrayOf(serverUdn, artist, top)
        return query(
            "WHERE server_udn=? AND res_url <> '' AND artist=?$topClause ORDER BY album, title",
            args
        )
    }

    private fun query(whereClause: String, args: Array<String>): List<IndexEntry> {
        val out = ArrayList<IndexEntry>()
        readableDatabase.rawQuery(
            """
            SELECT server_udn, object_id, title, artist, album, upnp_class, res_url, mime, art_url,
                   dedupe_key, track_key, top_id, top_title
            FROM items $whereClause
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    IndexEntry(
                        serverUdn = c.getString(0),
                        objectId = c.getString(1),
                        title = c.getString(2) ?: "",
                        artist = c.getString(3) ?: "",
                        album = c.getString(4) ?: "",
                        upnpClass = c.getString(5) ?: "",
                        resUrl = c.getString(6) ?: "",
                        mime = c.getString(7) ?: "",
                        artUrl = c.getString(8) ?: "",
                        dedupeKey = c.getString(9) ?: "",
                        trackKey = c.getString(10) ?: "",
                        topId = c.getString(11) ?: "",
                        topTitle = c.getString(12) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM items")
        writableDatabase.execSQL("DELETE FROM containers")
    }

    private companion object {
        const val DB_NAME = "media_index.db"
        const val DB_VERSION = 3
    }
}
