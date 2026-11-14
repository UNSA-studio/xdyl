package www.xdyl.hygge.com

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.loadingindicator.LoadingIndicator

/**
 * 断网时的全屏占位层。
 *
 * 设计口径（用户明确要求）：
 *  - 只要是"连不上"，一律用**大的**居中 LoadingIndicator，
 *    不管触发它的页面原本用的是 12dp 还是 24dp；
 *  - 下方固定三段文案：
 *       Can't Connect To The Internet!
 *       这可能是你的问题，也有可能是我们的问题，但目前无法知晓!
 *       请尝试重新连接 Internet/WIFI!
 *  - 网络恢复后自动消失（下次请求成功或 NetState 轮询到在线）。
 *
 * 与 [LoadingOverlay] 互斥：显示时先把常规加载遮罩撤掉，避免两个圈叠在一起。
 */
object OfflineOverlay {

    private const val SIZE_DP = 56

    private var host: Activity? = null
    private var root: FrameLayout? = null
    private var installed = false

    /** 当前是否正显示离线层 */
    @Volatile
    var showing: Boolean = false
        private set

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                host = a
                if (showing) attach(a)
            }

            override fun onActivityPaused(a: Activity) {
                if (host === a) {
                    detach()
                    host = null
                }
            }

            override fun onActivityDestroyed(a: Activity) {
                if (host === a) {
                    detach()
                    host = null
                }
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
        })
    }

    /** 显示离线层；可重复调用 */
    fun show() {
        if (showing) return
        showing = true
        // 常规加载遮罩让位，避免两个圈重叠
        LoadingOverlay.forceDismiss()
        val a = host
        if (a != null) a.runOnUiThread { attach(a) }
    }

    /** 网络恢复 / 请求成功 → 收起 */
    fun hide() {
        if (!showing) return
        showing = false
        detach()
    }

    private fun attach(activity: Activity) {
        if (root != null) return
        if (activity.isFinishing || activity.isDestroyed) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val d = activity.resources.displayMetrics.density

        val box = FrameLayout(activity).apply {
            // 不透明背景：让"没有内容"这件事看起来是刻意的，而不是白屏
            setBackgroundColor(0xFF101114.toInt())
            isClickable = true
            isFocusable = true
        }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val size = (SIZE_DP * d).toInt()
        val ind = LoadingIndicator(activity).apply {
            indicatorSize = size
            setIndicatorColor(0xFFA0C4FF.toInt())
            show()
        }
        col.addView(ind, LinearLayout.LayoutParams(size, size))

        // 固定三段文案，中英混排按用户给的原文
        col.addView(label(activity, "Can't Connect To The Internet!", 17f, 0xFFFFFFFF.toInt(), bold = true)
            .apply { setPadding(0, (24 * d).toInt(), 0, (6 * d).toInt()) })
        col.addView(label(activity, "这可能是你的问题，也有可能是我们的问题，但目前无法知晓!", 13f, 0xFFB9C0CC.toInt())
            .apply { setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0) })
        col.addView(label(activity, "请尝试重新连接 Internet/WIFI!", 13f, 0xFFB9C0CC.toInt())
            .apply { setPadding((28 * d).toInt(), (6 * d).toInt(), (28 * d).toInt(), 0) })

        box.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        content.addView(box, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        box.alpha = 0f
        box.animate().alpha(1f).setDuration(180L).start()
        col.scaleX = 0.92f
        col.scaleY = 0.92f
        col.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()

        root = box
    }

    private fun label(a: Activity, t: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(a).apply {
            text = t
            textSize = sp
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun detach() {
        val box = root ?: run {
            // 尚未 attach 也要清状态
            return
        }
        root = null
        val parent = box.parent as? ViewGroup
        box.animate().alpha(0f).setDuration(150L).withEndAction {
            parent?.removeView(box)
        }.start()
    }
}