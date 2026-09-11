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
        val artUrl: String
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
              title      TEXT,
              artist     TEXT,
              album      TEXT,
              upnp_class TEXT,
              res_url    TEXT,
              mime       TEXT,
              art_url    TEXT,
              updated_at INTEGER,
              PRIMARY KEY(server_udn, object_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_items_title ON items(title)")
        // 同一首歌的"文件地址"是唯一的：多个浏览视图（所有音乐/专辑/歌手/文件夹）
        // 会给出不同 objectID，但 res_url 相同 —— 用唯一索引保证一个文件只留一条
        db.execSQL("CREATE UNIQUE INDEX idx_items_res ON items(server_udn, res_url)")
        db.execSQL(
            """
            CREATE TABLE containers(
              server_udn TEXT NOT NULL,
              object_id  TEXT NOT NULL,
              parent_id  TEXT,
              title      TEXT,
              scanned    INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER,
              PRIMARY KEY(server_udn, object_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2：按 res_url 去重（清掉已经重复的旧索引），並建立唯一索引
        if (oldVersion < 2) {
            db.execSQL(
                """
                DELETE FROM items WHERE rowid NOT IN (
                  SELECT MIN(rowid) FROM items GROUP BY server_udn, res_url
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_items_res ON items(server_udn, res_url)")
        }
    }

    // ------------------------------------------------------------------
    // 写入（索引线程调用）
    // ------------------------------------------------------------------

    fun putItem(serverUdn: String, parentId: String, item: MediaItem, now: Long) {
        val values = ContentValues().apply {
            put("server_udn", serverUdn)
            put("object_id", item.id)
            put("parent_id", parentId)
            put("title", item.title)
            put("artist", item.artist)
            put("album", item.album)
            put("upnp_class", item.upnpClass)
            put("res_url", item.resUrl)
            put("mime", item.mime)
            put("art_url", item.artUrl)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict(
            "items", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun putContainer(serverUdn: String, objectId: String, parentId: String?, title: String, now: Long) {
        val values = ContentValues().apply {
            put("server_udn", serverUdn)
            put("object_id", objectId)
            put("parent_id", parentId)
            put("title", title)
            put("scanned", 0)
            put("updated_at", now)
        }
        // 已存在的目录不覆盖 scanned 状态（避免重复入队）
        writableDatabase.insertWithOnConflict(
            "containers", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** 取一个还没扫的目录（断点续扫）；没有则返回 null */
    fun nextUnscannedContainer(serverUdn: String): Pair<String, String>? {
        readableDatabase.rawQuery(
            "SELECT object_id, title FROM containers WHERE server_udn=? AND scanned=0 LIMIT 1",
            arrayOf(serverUdn)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val objectId: String = c.getString(0)
            val title: String = c.getString(1) ?: ""
            return Pair(objectId, title)
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
        // 用 LinkedHashMap 按 resUrl 去重（同一文件被多台服务器/多个视图暴露时只留一条）
        val unique = LinkedHashMap<String, IndexEntry>()
        readableDatabase.rawQuery(
            """
            SELECT server_udn, object_id, title, artist, album, upnp_class, res_url, mime, art_url
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
                    artUrl = c.getString(8) ?: ""
                )
                unique.putIfAbsent(entry.resUrl, entry)
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

    /** 按专辑分组（同一专辑名可能出现不同歌手，这里取其中一个歌手做提示） */
    fun albums(serverUdn: String): List<AlbumRow> {
        val out = ArrayList<AlbumRow>()
        readableDatabase.rawQuery(
            """
            SELECT album, MAX(artist) AS a, COUNT(*) AS c
            FROM items
            WHERE server_udn=? AND res_url <> '' AND album <> ''
            GROUP BY album
            ORDER BY album
            """.trimIndent(),
            arrayOf(serverUdn)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(AlbumRow(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2)))
            }
        }
        return out
    }

    /** 按歌手分组 */
    fun artists(serverUdn: String): List<ArtistRow> {
        val out = ArrayList<ArtistRow>()
        readableDatabase.rawQuery(
            """
            SELECT artist, COUNT(*) AS c, COUNT(DISTINCT album) AS al
            FROM items
            WHERE server_udn=? AND res_url <> '' AND artist <> ''
            GROUP BY artist
            ORDER BY artist
            """.trimIndent(),
            arrayOf(serverUdn)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ArtistRow(c.getString(0) ?: "", c.getInt(1), c.getInt(2)))
            }
        }
        return out
    }

    /** 全部歌曲（按歌名排序） */
    fun songs(serverUdn: String, limit: Int = 5000): List<IndexEntry> =
        query(
            "WHERE server_udn=? AND res_url <> '' ORDER BY title LIMIT ?",
            arrayOf(serverUdn, limit.toString())
        )

    fun songsByAlbum(serverUdn: String, album: String): List<IndexEntry> =
        query(
            "WHERE server_udn=? AND res_url <> '' AND album=? ORDER BY title",
            arrayOf(serverUdn, album)
        )

    fun songsByArtist(serverUdn: String, artist: String): List<IndexEntry> =
        query(
            "WHERE server_udn=? AND res_url <> '' AND artist=? ORDER BY album, title",
            arrayOf(serverUdn, artist)
        )

    private fun query(whereClause: String, args: Array<String>): List<IndexEntry> {
        val out = ArrayList<IndexEntry>()
        readableDatabase.rawQuery(
            """
            SELECT server_udn, object_id, title, artist, album, upnp_class, res_url, mime, art_url
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
                        artUrl = c.getString(8) ?: ""
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
        const val DB_VERSION = 2
    }
}
