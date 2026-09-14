package com.example.myupnp

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.myupnp.model.MediaItem

/** 把系统 MediaStore 已收录的音乐写入既有 SQLite 索引；不扫描任意文件路径。 */
class LocalMusicIndexer(
    private val context: Context,
    private val store: MediaIndexStore
) {
    fun rebuild(): Int {
        store.deleteServer(SOURCE_KEY) // MediaStore 是全量快照，重建前清理旧条目
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION
        )
        var count = 0
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.SIZE}>0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val item = MediaItem(
                    id = id.toString(),
                    title = cursor.getString(titleCol).orEmpty().ifBlank { "（未命名）" },
                    upnpClass = "object.item.audioItem.musicTrack",
                    resUrl = uri.toString(),
                    mime = cursor.getString(mimeCol).orEmpty(),
                    artist = cursor.getString(artistCol).orEmpty(),
                    album = cursor.getString(albumCol).orEmpty(),
                    sizeBytes = cursor.getLong(sizeCol),
                    durationSec = cursor.getLong(durationCol) / 1000L
                )
                if (store.putItem(SOURCE_KEY, ROOT_ID, ROOT_ID, "本机音乐", item, System.currentTimeMillis())) count++
            }
        }
        return count
    }

    companion object {
        const val SOURCE_KEY = "local:mediastore"
        private const val ROOT_ID = "local_music"
    }
}
