package com.example.myupnp

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * 设备列表条目：分组标题行 或 设备行
 * ------------------------------------------------------------------
 * 扫描到的设备按类型分组展示（MediaServer / 其他设备）。
 * 用 ListView + 自定义 adapter，支持两种行：
 *  - Header：分组标题（如 "MediaServer (3)"），不可点击、粗体、底色区分
 *  - Device：一台设备的多行文本；点击由外层 listener 用 entryKey 找回 Entry
 */
sealed class DeviceListItem {
    /** 分组标题 */
    data class Header(val title: String) : DeviceListItem()

    /** 一台设备 */
    data class DeviceItem(
        val entryKey: String, // Entry.location，用于从 entries 取回原对象
        val text: String
    ) : DeviceListItem()
}

/** 给 ListView 用的设备列表 adapter（两种 viewType：标题/设备） */
class DeviceListAdapter(
    private val context: Context,
    private val items: MutableList<DeviceListItem>
) : BaseAdapter() {

    private val dp = context.resources.displayMetrics.density

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): DeviceListItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int =
        if (items[position] is DeviceListItem.Header) TYPE_HEADER else TYPE_DEVICE

    override fun isEnabled(position: Int): Boolean =
        items[position] !is DeviceListItem.Header // 标题行不可点击

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return when (val item = items[position]) {
            is DeviceListItem.Header -> buildHeader(item.title)
            is DeviceListItem.DeviceItem -> buildDevice(item.text)
        }
    }

    /** 分组标题：粗体、浅灰底、上下留白，一眼区分 */
    private fun buildHeader(title: String): View {
        val tv = TextView(context).apply {
            text = title
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF37474F.toInt())
            setBackgroundColor(0xFFECEFF1.toInt())
            setPadding(px(12), px(8), px(12), px(8))
            textSize = 14f
        }
        return tv
    }

    /** 设备行：多行文本，与标题区分（浅色字、无背景） */
    private fun buildDevice(text: String): View {
        val tv = TextView(context).apply {
            this.text = text
            setPadding(px(12), px(8), px(12), px(8))
            textSize = 13f
        }
        return tv
    }

    private fun px(value: Int): Int = (value * dp).toInt()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_DEVICE = 1
    }
}
