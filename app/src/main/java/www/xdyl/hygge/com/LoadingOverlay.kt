package www.xdyl.hygge.com

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.loadingindicator.LoadingIndicator

/**
 * 全局加载指示器（M3 Expressive LoadingIndicator：会形变的几何图形）。
 *
 * 它不是"所有请求都亮"，而是按语义区分：
 *
 *   需要显示（页面级等待，用户没有别的反馈渠道）
 *     - 进入页面拉列表 / 详情
 *     - 弹窗首次加载内容
 *
 *   不需要显示（有别的反馈，或用户根本没在等）
 *     - 点赞、已读回执、发帖、领取、佩戴这类点击型 POST → 有 Toast / 局部刷新
 *     - 后台轮询、心跳、资料静默更新
 *     - 页面里已经有行内 spinner 的（否则会出现两个转圈）→ 用 quiet{} 包起来
 *     - 完成得太快的（< MIN_SHOW_MS）→ 不显示，避免闪一下更难看
 */
object LoadingOverlay {

    /** 短于这个时长完成的请求不显示，避免"闪一下" */
    private const val MIN_SHOW_MS = 240L

    private var silentDepth = 0      // quiet{} 嵌套深度
    private var visibleCount = 0     // 可见请求计数

    private var host: Activity? = null
    private var overlay: FrameLayout? = null
    private var indicator: LoadingIndicator? = null
    private var shownAt = 0L
    private var pendingHide: Runnable? = null

    @Volatile
    var enabled: Boolean = true

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                host = activity
                if (visibleCount > 0) attach(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (host === activity) {
                    detachNow()
                    host = null
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (host === activity) {
                    detachNow()
                    host = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /** 开始一次"需要显示"的等待 */
    fun begin() {
        val show = synchronized(this) {
            if (silentDepth > 0) return
            visibleCount++
            pendingHide?.let {
                host?.window?.decorView?.removeCallbacks(it)
                pendingHide = null
            }
            true
        }
        if (!show) return
        val a = host ?: return
        a.runOnUiThread { if (visibleCount > 0) attach(a) }
    }

    fun end() {
        val a = synchronized(this) {
            if (visibleCount > 0) visibleCount--
            if (visibleCount > 0) return
            host
        }
        if (a == null) {
            detachNow()
            return
        }
        val elapsed = System.currentTimeMillis() - shownAt
        val delay = (MIN_SHOW_MS - elapsed).coerceAtLeast(0)
        a.runOnUiThread {
            if (delay <= 0) {
                detachNow()
            } else {
                val r = Runnable { detachNow() }
                synchronized(this) { pendingHide = r }
                a.window.decorView.postDelayed(r, delay)
            }
        }
    }

    /**
     * 在这段代码内的请求不显示全局指示器（页面里已有自己的行内 spinner，
     * 或者这是后台静默请求）。
     */
    inline fun <T> quiet(block: () -> T): T {
        enterQuiet()
        return try {
            block()
        } finally {
            exitQuiet()
        }
    }

    @Synchronized
    fun enterQuiet() {
        silentDepth++
    }

    @Synchronized
    fun exitQuiet() {
        if (silentDepth > 0) silentDepth--
    }

    private fun attach(activity: Activity) {
        if (!enabled || overlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val d = activity.resources.displayMetrics.density
        val size = (22 * d).toInt()
        val margin = (10 * d).toInt()

        val ind = LoadingIndicator(activity).apply {
            indicatorSize = size
            setIndicatorColor(0xFFA0C4FF.toInt())
            // 必须 show() 才启动形变+旋转；只设 visible 会停在静止方块
            show()
        }

        val box = FrameLayout(activity).apply {
            isClickable = false
            isFocusable = false
            addView(ind, FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = margin
                marginEnd = margin
            })
        }

        root.addView(
            box,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size + margin * 2)
        )

        box.alpha = 0f
        box.animate().alpha(1f).setDuration(120L).start()

        overlay = box
        indicator = ind
        shownAt = System.currentTimeMillis()
    }

    private fun detachNow() {
        val ind = indicator
        val box = overlay
        indicator = null
        overlay = null
        shownAt = 0L
        synchronized(this) { pendingHide = null }
        if (box == null) return
        val parent = box.parent as? ViewGroup
        box.animate().alpha(0f).setDuration(100L).withEndAction {
            ind?.hide()
            parent?.removeView(box)
        }.start()
    }
}