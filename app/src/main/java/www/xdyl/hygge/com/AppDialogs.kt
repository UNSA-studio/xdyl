package www.xdyl.hygge.com

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 【通用提示框模块】
 *
 * 统一全 App 的弹窗风格（深色主题 + R.style.DialogAnimation），
 * 其它页面直接调用即可，不要再重复写 dialog 逻辑。
 *
 * 用法：
 * ```
 * AppDialogs.tip(this, "尚未完成任务目标", "完成之后再来领取吧")
 * AppDialogs.confirm(this, "确认购买", "确定要购买吗？") { doBuy() }
 * AppDialogs.choose(this, "选择版块", names) { index -> ... }
 * ```
 */
object AppDialogs {

    /** 单按钮提示框（默认「知道了」） */
    fun tip(
        activity: AppCompatActivity,
        title: String,
        message: String,
        button: String = "知道了"
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(button, null)
            .show()
    }

    /** 双按钮确认框 */
    fun confirm(
        activity: AppCompatActivity,
        title: String,
        message: String,
        positive: String = "确定",
        negative: String = "取消",
        onConfirm: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton(negative, null)
            .show()
    }

    /** 列表选择框 */
    fun choose(
        activity: AppCompatActivity,
        title: String,
        items: Array<String>,
        negative: String = "取消",
        onPick: (Int) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle(title)
            .setItems(items) { _, which -> onPick(which) }
            .setNegativeButton(negative, null)
            .show()
    }
}