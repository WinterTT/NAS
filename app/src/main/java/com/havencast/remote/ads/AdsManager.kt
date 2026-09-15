package com.havencast.remote.ads

import android.app.Activity
import android.app.Application
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.havencast.remote.R
import java.lang.ref.WeakReference

/**
 * 广告（AdMob）：同意流程 → SDK 初始化 → 自适应横幅。
 * ------------------------------------------------------------------
 * 产品原则（与"精简易用"一致）：**只在媒体库页底部放一条横幅**，
 * 播放页/搜索页/对话框里一概不放，也永远不做插屏打断用户。
 *
 * 三条纪律：
 *  1. **不阻塞**：同意流程与 SDK 初始化都是异步的，失败也只是"没广告"，
 *     绝不影响扫描设备、浏览曲库、推送播放这些主流程。
 *  2. **失败即隐藏**：拉不到广告就把容器收起来，不留白条。
 *  3. **不碰业务**：本类只认识一个 FrameLayout 容器，不认识任何业务状态；
 *     是否展示由 [AdPolicy] 决定（免广告后连 AdView 都不创建）。
 *
 * 生命周期：Activity 在 onResume/onPause/onDestroy 转发给 [resume]/[pause]/[destroy]。
 */
class AdsManager(private val app: Application) {

    enum class State { IDLE, CONSENT, LOADING, READY, FAILED, DISABLED }

    private var consentInformation: ConsentInformation? = null
    private var sdkInitialized = false
    private var initializing = false
    private var canRequestAds = false
    private var adView: AdView? = null

    /** 容器与宿主 Activity 都用弱引用：别让广告把已销毁的页面拖住 */
    private var containerRef: WeakReference<FrameLayout>? = null
    private var activityRef: WeakReference<Activity>? = null

    private var state: State = State.IDLE
        set(value) {
            if (field == value) return
            field = value
            Log.i(TAG, "[AD] 状态 -> $value")
        }

    val currentState: State get() = state

    /** 广告是否处于开启状态（免广告后为 false） */
    val enabled: Boolean get() = AdPolicy.adsEnabled(app)

    // ------------------------------------------------------------------
    // 同意流程 + 初始化
    // ------------------------------------------------------------------

    /**
     * 走 Google UMP 同意流程并初始化 SDK。可重复调用（只生效一次）。
     *
     * EEA/英国等地区会弹同意表单；中国大陆等地区通常直接返回"无需征得同意"，
     * 用户不会被多弹一个窗口。想验证表单本身，可按官方文档用
     * ConsentDebugSettings 指定调试地区 + 测试设备（不要写进正式代码）。
     */
    fun initialize(activity: Activity) {
        if (initializing) return
        initializing = true
        if (!enabled) {
            state = State.DISABLED
            Log.i(TAG, "[AD] 已免广告，不初始化 SDK")
            return
        }
        state = State.CONSENT
        val info = UserMessagingPlatform.getConsentInformation(activity)
        consentInformation = info
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                // 同意状态更新成功：需要时展示表单（表单是否展示不影响能否请求广告）
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "[AD] 同意表单未展示：${formError.message}")
                    }
                    canRequestAds = info.canRequestAds()
                    Log.i(
                        TAG,
                        "[AD] 同意状态=${info.consentStatus}，可请求广告=$canRequestAds"
                    )
                    startSdk()
                }
            },
            { requestError ->
                // 拿不到同意信息：保守处理——不能请求广告，但 App 一切照常
                canRequestAds = info.canRequestAds()
                Log.w(TAG, "[AD] 同意信息获取失败：${requestError.message}")
                startSdk()
            }
        )
    }

    private fun startSdk() {
        if (!canRequestAds) {
            state = State.DISABLED
            Log.i(TAG, "[AD] 无广告请求权限，跳过 SDK 初始化")
            return
        }
        MobileAds.initialize(app) { _ ->
            sdkInitialized = true
            Log.i(TAG, "[AD] SDK 初始化完成")
            refresh()
        }
    }

    // ------------------------------------------------------------------
    // 横幅
    // ------------------------------------------------------------------

    /** 把横幅挂到容器里；容器为 null 容器不可用或已免广告时不显示 */
    fun attachBanner(activity: Activity, container: FrameLayout) {
        activityRef = WeakReference(activity)
        containerRef = WeakReference(container)
        // 等一帧拿到容器真实宽度（自适应横幅按宽度算高度）
        container.post { refresh() }
    }

    private fun refresh() {
        val container = containerRef?.get()
        val activity = activityRef?.get() ?: return
        if (container == null) return

        if (!enabled) {
            state = State.DISABLED
            destroyBanner()
            container.visibility = View.GONE
            return
        }
        if (!canRequestAds || !sdkInitialized) {
            // 同意流程/SDK 还没就绪：先藏着，就绪后 startSdk() 会再调回来
            container.visibility = View.GONE
            return
        }
        if (adView != null) {
            container.visibility = View.VISIBLE
            return
        }

        val metrics = activity.resources.displayMetrics
        val widthPx = if (container.width > 0) container.width else metrics.widthPixels
        val widthDp = (widthPx / metrics.density).toInt()
        // 锚定式自适应横幅（紧贴页面底部，正是我们的位置）。
        // 该 API 已被官方标记 deprecated（转向 inline adaptive），但 25.4.0 实测仍正常出广告；
        // 若将来不出广告，换成 AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize 即可。
        @Suppress("DEPRECATION")
        val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)

        state = State.LOADING
        val view = AdView(activity).apply {
            adUnitId = activity.getString(R.string.admob_banner_unit_id)
            setAdSize(adSize)
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    state = State.READY
                    container.visibility = View.VISIBLE
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // 拉不到就彻底收起，不留空白；下次进入页面会重新尝试
                    state = State.FAILED
                    Log.w(TAG, "[AD] 横幅加载失败：${error.code} ${error.message}")
                    container.visibility = View.GONE
                }
            }
        }
        container.removeAllViews()
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        adView = view
        container.visibility = View.GONE // 加载成功前不占位
        view.loadAd(AdRequest.Builder().build())
    }

    /** 免广告开关变化后调用（将来 Billing / 激励视频用） */
    fun onPolicyChanged() {
        refresh()
    }

    fun resume() {
        adView?.resume()
    }

    fun pause() {
        adView?.pause()
    }

    /** Activity 销毁：销毁 AdView 并断开引用（避免拿住已销毁的 Activity） */
    fun destroy() {
        destroyBanner()
        activityRef = null
        containerRef = null
    }

    private fun destroyBanner() {
        adView?.let { view ->
            (view.parent as? FrameLayout)?.removeView(view)
            view.destroy()
        }
        adView = null
    }

    private companion object {
        const val TAG = "HavenCast"
    }
}
