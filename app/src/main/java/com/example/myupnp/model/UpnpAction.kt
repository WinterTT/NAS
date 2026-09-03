package com.example.myupnp.model

/**
 * SCPD 中的一个"动作"（action）—— 服务能被外部调用的一件事
 * ------------------------------------------------------------------
 * SCPD(Service Control Protocol Description) 是服务的"使用说明书"：
 * 它列出这个服务支持哪些动作，以及每个动作的输入/输出参数。
 *
 * 例如 AVTransport 服务的动作：
 *   - Play(InstanceID, Speed)            开始播放
 *   - Pause(InstanceID)                  暂停
 *   - SetAVTransportURI(InstanceID, CurrentURI, CurrentURIMetaData) 设定播放源
 *
 * 参数分两种方向：
 *   - direction = "in"   调用方要传进去的值（请求里带上）
 *   - direction = "out"  设备算出来返回的值（在响应里读）
 * 每个参数还指向一个相关状态变量（relatedStateVariable），决定类型/范围。
 */
data class UpnpArgument(
    val name: String = "",
    val direction: String = "in",        // "in" | "out"
    val relatedStateVariable: String = ""
)

data class UpnpAction(
    val name: String = "",
    val arguments: List<UpnpArgument> = emptyList()
) {
    /** 调用时我们只需要填 in 参数 */
    val inArguments: List<UpnpArgument> get() = arguments.filter { it.direction == "in" }

    /** 响应里设备会回填 out 参数 */
    val outArguments: List<UpnpArgument> get() = arguments.filter { it.direction == "out" }

    /** 展示用签名，如 Play(in: InstanceID, Speed) -> out 无 */
    fun signature(): String =
        buildString {
            append(name)
            append("(in: ")
            append(inArguments.joinToString(", ") { it.name })
            append(")")
            if (outArguments.isNotEmpty()) {
                append(" => ")
                append(outArguments.joinToString(", ") { it.name })
            }
        }
}
