package com.example.myupnp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * 设备列表条目：分组标题行 或 设备行
 * ------------------------------------------------------------------
 *  - Header：分组标题（如 "MediaServer (3)"），不可点击
 *  - Device：一台设备（名称 + 副信息两行卡片）；点击由外层 listener
 *    用 entryKey 找回 Entry
 */
sealed class DeviceListItem {
    /** 分组标题 */
    data class Header(val title: String) : DeviceListItem()

    /** 一台设备（卡片两行） */
    data class DeviceItem(
        val entryKey: String, // Entry.location，用于从 entries 取回原对象
        val name: String,     // 第一行：⭐别名/原名（已含收藏标记）
        val sub: String       // 第二行小字：类型 · 型号 · IP
    ) : DeviceListItem()
}

/** 给 ListView 用的设备列表 adapter（标题/设备两种行） */
class DeviceListAdapter(
    private val context: Context,
    private val items: MutableList<DeviceListItem>
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

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
            is DeviceListItem.Header -> bindHeader(convertView, parent, item.title)
            is DeviceListItem.DeviceItem -> bindDevice(convertView, parent, item)
        }
    }

    private fun bindHeader(convert: View?, parent: ViewGroup, title: String): View {
        val view = convert ?: inflater.inflate(R.layout.item_group_header, parent, false)
        view.findViewById<TextView>(R.id.tvGroupTitle).text = title
        return view
    }

    private fun bindDevice(convert: View?, parent: ViewGroup, item: DeviceListItem.DeviceItem): View {
        val view = convert ?: inflater.inflate(R.layout.item_device, parent, false)
        view.findViewById<TextView>(R.id.tvDeviceName).text = item.name
        view.findViewById<TextView>(R.id.tvDeviceSub).text = item.sub
        return view
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_DEVICE = 1
    }
}
