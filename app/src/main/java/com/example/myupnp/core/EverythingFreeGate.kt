package com.example.myupnp.core

/**
 * 当前实现：一切功能免费（开发期默认）。
 * 以后接收费时新增 SubscriptionFeatureGate 并在注入点替换它。
 */
object EverythingFreeGate : FeatureGate {
    override fun canUse(feature: FeatureId): Boolean = true

    override fun upgradeMessage(feature: FeatureId): String =
        "该功能需要升级（当前版本尚未接入付费）"
}
