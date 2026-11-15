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

    /**
     * 单行输入框。
     *
     * @param inputType  EditText inputType，如密码框用
     *   TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD
     * @param onSubmit   点确认时回调，参数为输入内容（已 trim）
     */
    fun input(
        activity: AppCompatActivity,
        title: String,
        message: String? = null,
        hint: String? = null,
        preset: String? = null,
        positive: String = "确定",
        negative: String = "取消",
        inputType: Int = android.text.InputType.TYPE_CLASS_TEXT,
        onSubmit: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val et = android.widget.EditText(activity).apply {
            this.hint = hint
            this.inputType = inputType
            this.setText(preset)
            setSingleLine(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            if (!message.isNullOrBlank()) {
                addView(android.widget.TextView(activity).apply {
                    text = message
                    textSize = 14f
                    setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                })
            }
            addView(et)
        }
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(positive) { _, _ -> onSubmit(et.text?.toString()?.trim().orEmpty()) }
            .setNegativeButton(negative, null)
            .show()
        // 全选预置内容，方便「查看+复制」场景一键覆盖
        et.post { et.selectAll() }
    }
}