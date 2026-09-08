package com.example.myupnp

import android.content.Context

/**
 * 设备级用户数据（第 7 课 E：收藏 / 别名）
 * ------------------------------------------------------------------
 * 用"设备稳定 ID"（优先 UDN，见 [MainViewModel.stableDeviceId]）做键，
 * 这样重启后设备的 IP（LOCATION）变了，收藏与别名仍能认回同一台设备。
 *
 * 纯存取，不碰 UI；由 MainViewModel 持有并在其方法里读写。
 */
class DeviceBookmarks(context: Context) {

    private val prefs = context.getSharedPreferences("device_meta", Context.MODE_PRIVATE)

    fun isFavorite(id: String): Boolean = prefs.getBoolean("fav_$id", false)

    /** 当前一共收藏了几台（含离线没被发现的） */
    fun favoriteTotal(): Int =
        prefs.all.keys.count { it.startsWith(PREFIX_FAV) && prefs.getBoolean(it, false) }

    fun setFavorite(id: String, favorite: Boolean) {
        prefs.edit().putBoolean(PREFIX_FAV + id, favorite).apply()
    }

    fun alias(id: String): String? =
        prefs.getString(PREFIX_ALIAS + id, null)?.takeIf { it.isNotBlank() }

    fun setAlias(id: String, name: String) {
        prefs.edit().putString(PREFIX_ALIAS + id, name.trim()).apply()
    }

    fun clearAlias(id: String) {
        prefs.edit().remove(PREFIX_ALIAS + id).apply()
    }

    private companion object {
        const val PREFIX_FAV = "fav_"
        const val PREFIX_ALIAS = "alias_"
    }
}
