package com.example.myupnp

import android.os.Handler
import android.util.Log
import com.example.myupnp.gena.GenaClient
import com.example.myupnp.model.UpnpService
import java.util.concurrent.ExecutorService

/**
 * GENA 事件订阅管理器（第 3 课，重构后独立成类）
 * ------------------------------------------------------------------
 * 职责：管理对一个/多个 UPnP 服务的"事件订阅"生命周期：
 *   - 订阅（SUBSCRIBE，带本机回调地址）
 *   - 续订（到期前自动 RENEW）
 *   - 退订（UNSUBSCRIBE）/ 全部退订
 *   - SID 反查（收到事件时知道是哪个服务推来的）
 *
 * 网络操作丢给 [controlExecutor]，回调经 [mainHandler] 回主线程。
 * 本类不碰 View；需要提示用户时通过 [Listener] 转交 Activity。
 */
class SubscriptionManager(
    private val mainHandler: Handler,
    private val controlExecutor: ExecutorService,
    private val listener: Listener
) {

    /** 供 UI 反馈订阅操作结果 */
    interface Listener {
        fun onInfo(message: String)   // 普通提示（成功等）
        fun onError(message: String)  // 错误提示
    }

    /** 一次订阅的记录：key = eventSubUrl */
    data class Subscription(
        val eventSubUrl: String,
        val serviceName: String,
        var sid: String,
        var timeoutSec: Int
    )

    private val subscriptions = HashMap<String, Subscription>()

    /** 续订定时器：到期前自动再 SUBSCRIBE 一次（key = eventSubUrl） */
    private val renewRunnables = HashMap<String, Runnable>()

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    fun isSubscribed(eventSubUrl: String): Boolean =
        subscriptions.containsKey(eventSubUrl)

    /** 通过 SID 反查服务订阅 URL（收到事件推送时用于定位是哪个服务） */
    fun eventUrlBySid(sid: String?): String? =
        subscriptions.entries.firstOrNull { it.value.sid == sid }?.key

    fun snapshot(): List<Subscription> = subscriptions.values.toList()

    // ------------------------------------------------------------------
    // 订阅 / 续订 / 退订（都在主线程发起）
    // ------------------------------------------------------------------

    /** 订阅一个服务的事件推送 */
    fun subscribe(service: UpnpService, callbackUrl: String) {
        val eventUrl = service.eventSubUrl
        if (eventUrl.isBlank()) {
            listener.onError("该服务没有 eventSubURL")
            return
        }
        if (subscriptions.containsKey(eventUrl)) {
            listener.onInfo("已在订阅中，正在续订")
            renew(eventUrl)
            return
        }
        val serviceName = serviceNameOf(service)
        Log.d(TAG, "[GENA>] SUBSCRIBE $eventUrl CALLBACK=$callbackUrl")
        controlExecutor.execute {
            val result = GenaClient.subscribe(eventUrl, callbackUrl)
            mainHandler.post {
                if (result.ok) {
                    val sub = Subscription(
                        eventSubUrl = eventUrl,
                        serviceName = serviceName,
                        sid = result.sid,
                        timeoutSec = result.timeoutSec.let { if (it <= 0) 1800 else it }
                    )
                    subscriptions[eventUrl] = sub
                    Log.i(TAG, "[GENA<] 订阅成功 ${sub.serviceName} SID=${result.sid}")
                    listener.onInfo("订阅成功 SID=${result.sid}，已安排自动续订")
                    scheduleRenewal(eventUrl)
                } else {
                    Log.w(TAG, "[GENA!] 订阅失败 $eventUrl: ${result.message}")
                    listener.onError("订阅失败: ${result.message}")
                }
            }
        }
    }

    /** 到期前自动续订（手动或定时触发） */
    fun renew(eventUrl: String) {
        val sub = subscriptions[eventUrl] ?: return
        Log.d(TAG, "[GENA] RENEW ${sub.serviceName} $eventUrl SID=${sub.sid}")
        controlExecutor.execute {
            val result = GenaClient.renew(eventUrl, sub.sid, 1800)
            mainHandler.post {
                if (result.ok) {
                    sub.sid = result.sid.ifEmpty { sub.sid }
                    sub.timeoutSec = result.timeoutSec.let { if (it <= 0) 1800 else it }
                    Log.i(TAG, "[GENA] 续订成功，新 TIMEOUT=${sub.timeoutSec}s")
                    scheduleRenewal(eventUrl)
                } else {
                    Log.w(TAG, "[GENA!] 续订失败: ${result.message}")
                    listener.onError("续订失败: ${result.message}（订阅可能已失效）")
                    subscriptions.remove(eventUrl)
                    renewRunnables.remove(eventUrl)?.let { mainHandler.removeCallbacks(it) }
                }
            }
        }
    }

    /** 退订某个服务 */
    fun unsubscribe(service: UpnpService) {
        val eventUrl = service.eventSubUrl
        val sub = subscriptions[eventUrl]
        if (sub == null) {
            listener.onError("该服务尚未订阅")
            return
        }
        Log.d(TAG, "[GENA] UNSUBSCRIBE ${sub.serviceName} SID=${sub.sid}")
        controlExecutor.execute {
            val result = GenaClient.unsubscribe(eventUrl, sub.sid)
            mainHandler.post {
                if (result.ok) {
                    listener.onInfo("退订成功")
                } else {
                    listener.onError("退订失败: ${result.message}")
                }
                subscriptions.remove(eventUrl)
                renewRunnables.remove(eventUrl)?.let { mainHandler.removeCallbacks(it) }
            }
        }
    }

    /** 退订某个 eventSubUrl（不需要持有完整 service 时用） */
    fun unsubscribeByUrl(eventSubUrl: String) {
        val sub = subscriptions[eventSubUrl] ?: return
        subscriptions.remove(eventSubUrl)
        renewRunnables.remove(eventSubUrl)?.let { mainHandler.removeCallbacks(it) }
        Log.d(TAG, "[GENA] 清理订阅 ${sub.serviceName} @ $eventSubUrl")
        controlExecutor.execute {
            GenaClient.unsubscribe(eventSubUrl, sub.sid)
        }
    }

    /** 全部退订（停止扫描 / Activity 销毁时调用） */
    fun unsubscribeAll() {
        val snapshot = subscriptions.values.toList()
        for (sub in snapshot) {
            Log.d(TAG, "[GENA] 清理: UNSUBSCRIBE ${sub.serviceName}")
            controlExecutor.execute {
                GenaClient.unsubscribe(sub.eventSubUrl, sub.sid)
            }
        }
        subscriptions.clear()
        renewRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        renewRunnables.clear()
    }

    // ------------------------------------------------------------------
    // 续订调度
    // ------------------------------------------------------------------

    private fun scheduleRenewal(eventUrl: String) {
        val sub = subscriptions[eventUrl] ?: return
        renewRunnables.remove(eventUrl)?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { renew(eventUrl) }
        renewRunnables[eventUrl] = runnable
        val delayMs = (sub.timeoutSec * 1_000L / 2).coerceAtLeast(5_000L)
        mainHandler.postDelayed(runnable, delayMs)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun serviceNameOf(service: UpnpService): String {
        val fromId = service.serviceId.substringAfterLast(':')
        if (fromId.isNotBlank()) return fromId
        return service.serviceType.substringAfter(":service:").substringBefore(":")
            .ifBlank { service.serviceType }
    }

    companion object {
        private const val TAG = "MyUPNP"
    }
}
