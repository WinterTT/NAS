package com.example.myupnp.core

/**
 * 功能门：判断"某功能当前是否可用"的唯一入口（收费口子）
 * ------------------------------------------------------------------
 * 将来接付费（Google Play Billing / 订阅 / 买断）时：
 *   1. 写一个 SubscriptionFeatureGate，内部查询购买状态；
 *   2. 在注入点把实现换成它 —— 业务代码完全不用改。
 *
 * 业务层用法（示例）：
 *   if (!gate.canUse(FeatureId.DEVICE_LIMIT) && registry.size >= 5) {
 *       gate.promptUpgrade(activity)   // 弹"升级会员"
 *       return
 *   }
 */
interface FeatureGate {

    /** 该功能是否可用（免费判断 / 已购判断） */
    fun canUse(feature: FeatureId): Boolean

    /** 方便 UI 层统一调用：不可用时的提示文案 */
    fun upgradeMessage(feature: FeatureId): String

    /** 是否展示"去升级"入口（不可用且非测试环境时一般要展示） */
    fun shouldShowUpgradeEntry(feature: FeatureId): Boolean =
        !canUse(feature)
}
