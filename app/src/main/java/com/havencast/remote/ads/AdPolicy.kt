package com.havencast.remote.ads

import android.content.Context

/**
 * 广告策略：现在只回答一个问题——**当前该不该显示广告**。
 * ------------------------------------------------------------------
 * 数据存在 SharedPreferences 里，是"用户已免广告"这个事实的唯一来源。
 * 之所以单独抽出来，是为了让广告代码不关心"怎么免广告"：
 *
 *   - 将来接 Google Play Billing 的一次性购买（FeatureId.NO_ADS）时，
 *     购买成功回调里调 [setNoAdsUnlocked](context, true) 即可；
 *   - 将来用激励视频换"当天免广告"也走同一个开关。
 *
 * 与 [com.havencast.remote.core.FeatureGate] 的关系：
 * FeatureGate 管的是"功能能不能用"，广告开关属于"变现状态"，
 * 二者都会在 Billing 落地时接到同一处购买结果上。
 */
object AdPolicy {

    private const val PREFS = "ads_policy"
    private const val KEY_NO_ADS = "no_ads_unlocked"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否已免广告（买断 / 激励视频兑换） */
    fun isNoAdsUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NO_ADS, false)

    fun setNoAdsUnlocked(context: Context, unlocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_NO_ADS, unlocked).apply()
    }

    /** 当前是否允许展示广告 */
    fun adsEnabled(context: Context): Boolean = !isNoAdsUnlocked(context)
}
