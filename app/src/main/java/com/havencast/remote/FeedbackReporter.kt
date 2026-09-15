package com.havencast.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * 意见反馈的"成文与投递"。
 *
 * 分工：Activity 只负责收集用户输入（类型 / 描述 / 联系方式 / 是否附带环境信息），
 * 本类负责把内容整理成一段纯文本，并选一个能用的出口发出去：
 *   1) 配了反馈邮箱 → 直接写邮件（mailto）
 *   2) 否则 → 系统分享（用户自己选邮件 / 微信 / 备忘录等）
 *   3) 都没有 → 复制到剪贴板，让用户自己贴给我们
 *
 * 不依赖任何 View，也不依赖网络（App 没有自己的后台，反馈走系统渠道）。
 */
object FeedbackReporter {

    /**
     * 反馈邮箱。留空 = 不写死邮箱，走"系统分享 / 复制"。
     * 发布前把它换成真实收件地址（例如 havencast@xxx.com），
     * 设置页的按钮会自动从「分享反馈」变成「发送邮件」。
     */
    const val FEEDBACK_EMAIL = ""

    /** 投递结果：Activity 据此给出对应提示 */
    enum class Channel { MAIL, SHARE, CLIPBOARD }

    data class Draft(
        /** 反馈类型标签（来自 strings.xml 的 feedback_types） */
        val type: String,
        /** 用户填写的正文 */
        val detail: String,
        /** 可选联系方式 */
        val contact: String,
        /** 勾选了"附带设备信息"时传环境信息，否则传 null */
        val extraInfo: String?,
    )

    fun hasMailTarget(): Boolean = FEEDBACK_EMAIL.isNotBlank()

    /** 邮件主题 / 分享标题 */
    fun subjectOf(context: Context, draft: Draft): String =
        context.getString(R.string.app_name) + " 反馈 · " + draft.type

    /** 反馈正文（邮件与剪贴板共用） */
    fun buildBody(context: Context, draft: Draft): String = buildString {
        append("【").append(draft.type).append("】\n\n")
        append(draft.detail.trim()).append('\n')
        if (draft.contact.isNotBlank()) {
            append("\n联系方式：").append(draft.contact.trim()).append('\n')
        }
        if (!draft.extraInfo.isNullOrBlank()) {
            append("\n---------- 设备与版本信息 ----------\n")
            append(draft.extraInfo.trim()).append('\n')
        }
        append("\n（本邮件由 ").append(context.getString(R.string.app_name)).append(" 意见反馈生成）")
    }

    /**
     * 设备与版本信息：出问题时这几行基本能定位一半原因，
     * 用户在设置页可以自行取消勾选。
     */
    fun buildInfo(context: Context, appLines: List<String>): String {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = info?.versionName ?: context.getString(R.string.version_unknown)
        val versionCode: Long = if (info == null) {
            0L
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return buildString {
            append(context.getString(R.string.app_name)).append(' ')
            append(versionName).append(" (").append(versionCode).append(")\n")
            append("Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append(Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            append(' ').append(Build.MODEL).append('\n')
            for (line in appLines) {
                if (line.isNotBlank()) append(line).append('\n')
            }
        }.trim()
    }

    /** 把反馈发出去；返回实际使用的出口 */
    fun send(activity: Activity, draft: Draft): Channel {
        val subject = subjectOf(activity, draft)
        val body = buildBody(activity, draft)

        if (hasMailTarget()) {
            val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL")).apply {
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            if (canHandle(activity, mail)) {
                return runCatching {
                    activity.startActivity(mail)
                    Channel.MAIL
                }.getOrElse { share(activity, subject, body) }
            }
        }
        return share(activity, subject, body)
    }

    private fun share(activity: Activity, subject: String, body: String): Channel {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (hasMailTarget()) putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        }
        if (!canHandle(activity, share)) {
            copyToClipboard(activity, subject + "\n\n" + body)
            return Channel.CLIPBOARD
        }
        val chooser = Intent.createChooser(share, activity.getString(R.string.feedback_share_title))
        return runCatching {
            activity.startActivity(chooser)
            Channel.SHARE
        }.getOrElse {
            copyToClipboard(activity, subject + "\n\n" + body)
            Channel.CLIPBOARD
        }
    }

    /**
     * 是否有应用能处理这个 Intent。
     * 注意：Android 11+ 的包可见性限制会让"看不见的应用"也返回 null，
     * 所以只把它当尽力而为的判断，真正的失败由调用处的 try/catch 兜住。
     */
    private fun canHandle(context: Context, intent: Intent): Boolean = runCatching {
        @Suppress("DEPRECATION")
        intent.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    fun copyToClipboard(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.feedback_entry), text)
        )
    }

    /** 复制成功的提示：Android 13+ 系统自己会弹窗，避免重复提示 */
    fun toastCopied(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.feedback_copy_done, Toast.LENGTH_SHORT).show()
        }
    }
}
