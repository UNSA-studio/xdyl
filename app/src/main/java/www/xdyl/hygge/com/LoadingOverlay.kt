package www.xdyl.hygge.com

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.loadingindicator.LoadingIndicator

/**
 * 全局加载指示器（M3 Expressive LoadingIndicator：会形变的几何图形）。
 *
 * 设计目标：不再逐个页面手动加转圈，而是挂在 Application 上做统一出口。
 * 用法：
 *   - 计数式：begin() / end()，可嵌套，计数归零才消失
 *   - 自动绑定网络请求：ApiClient 内部统一调用，业务代码不用管
 *
 * 显示位置：覆盖在当前 Activity 的 content 根布局之上，右上角，
 * 不拦截触摸（除 LoadingIndicator 本身外），不影响用户操作。
 */
object LoadingOverlay {

    /** 正在进行的加载数量，支持并发嵌套 */
    private var counter = 0

    /** 当前正在展示的宿主 Activity */
    private var host: Activity? = null
    private var overlay: FrameLayout? = null
    private var indicator: LoadingIndicator? = null

    /** 是否启用（可在设置里关掉） */
    @Volatile
    var enabled: Boolean = true

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                host = activity
                if (counter > 0) attach(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (host === activity) {
                    detach()
                    host = null
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (host === activity) {
                    detach()
                    host = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /** 开始一次加载（线程安全，任意线程可调用） */
    @Synchronized
    fun begin() {
        counter++
        val a = host ?: return
        a.runOnUiThread { if (counter > 0) attach(a) }
    }

    /** 结束一次加载 */
    @Synchronized
    fun end() {
        if (counter > 0) counter--
        if (counter == 0) {
            val a = host ?: return
            a.runOnUiThread { detach() }
        }
    }

    /** 带返回值包裹：runCatching 语义，保证异常时也会 end() */
    inline fun <T> wrap(block: () -> T): T {
        begin()
        return try {
            block()
        } finally {
            end()
        }
    }

    private fun attach(activity: Activity) {
        if (!enabled) return
        if (overlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val size = (26 * activity.resources.displayMetrics.density).toInt()
        val margin = (10 * activity.resources.displayMetrics.density).toInt()

        val ind = LoadingIndicator(activity).apply {
            indicatorSize = size
            setIndicatorColor(0xFFA0C4FF.toInt())
        }

        // 只占右上角一小块，不挡整页；其余区域不拦截触摸
        val box = FrameLayout(activity).apply {
            isClickable = false
            isFocusable = false
            addView(ind, FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = margin
                marginEnd = margin
            })
        }

        root.addView(box, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // 让遮罩只在小方块范围内吃触摸，其他位置穿透
        box.setOnTouchListener { v, _ ->
            v.performClick()
            true
        }

        ind.alpha = 0f
        ind.animate().alpha(1f).setDuration(140L).start()

        overlay = box
        indicator = ind
    }

    private fun detach() {
        val ind = indicator
        val box = overlay
        indicator = null
        overlay = null
        if (box == null) return
        val parent = box.parent as? ViewGroup
        if (ind != null) {
            ind.animate().alpha(0f).setDuration(120L).withEndAction {
                parent?.removeView(box)
            }.start()
        } else {
            parent?.removeView(box)
        }
    }
}
