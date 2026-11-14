package www.xdyl.hygge.com

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.loadingindicator.LoadingIndicator

/**
 * 全局加载指示器 —— 严格按 M3 Expressive 规范实现。
 *
 * 规范要点（m3.material.io/components/loading-indicator）：
 *   1. 默认尺寸 48dp；可调范围 24–240dp
 *   2. 加载"页面或容器"时，必须**居中于该元素**（不是右上角）
 *   3. 有 content 叠在别的内容上时，用 container 提供对比
 *   4. 200ms 以下的等待不显示（Instant → No indicator）
 *   5. 200ms–5s 用 LoadingIndicator；超过 5s 应改用进度条
 *   6. 绝不用在"没有实际加载"的地方（账户资料这类不算）
 *
 * 因此本类只在页面需要整页等待时使用；点击型操作、页面自带局部 spinner 的，
 * 一律用 quiet{} 跳过。
 */
object LoadingOverlay {

    /** 规范：默认 48dp */
    private const val SIZE_DP = 48

    /** 规范：Instant（<200ms）不显示；留一点余量避免闪 */
    private const val MIN_SHOW_MS = 200L

    private var silentDepth = 0
    private var visibleCount = 0

    private var host: Activity? = null
    private var overlay: FrameLayout? = null
    private var indicator: LoadingIndicator? = null
    private var shownAt = 0L
    private var pendingHide: Runnable? = null

    @Volatile
    var enabled: Boolean = true

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                host = a
                if (visibleCount > 0) attach(a)
            }

            override fun onActivityPaused(a: Activity) {
                if (host === a) {
                    detachNow()
                    host = null
                }
            }

            override fun onActivityDestroyed(a: Activity) {
                if (host === a) {
                    detachNow()
                    host = null
                }
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
        })
    }

    fun begin() {
        synchronized(this) {
            if (silentDepth > 0) return
            visibleCount++
            pendingHide?.let {
                host?.window?.decorView?.removeCallbacks(it)
                pendingHide = null
            }
        }
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

    /** 这段代码里的请求不显示全局指示器 */
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
        val size = (SIZE_DP * d).toInt()

        // 规范：放在内容之上时用 container 提高对比度 → 圆形底
        val ind = LoadingIndicator(activity).apply {
            indicatorSize = size
            setIndicatorColor(0xFFA0C4FF.toInt())
            setContainerColor(0xCC141414.toInt())
            // 必须 show() 才启动形变+旋转
            show()
        }

        // 规范：加载页面时居中于该页面
        val box = FrameLayout(activity).apply {
            isClickable = true      // 等待期间挡住误触
            isFocusable = true
            addView(ind, FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            })
        }

        root.addView(box, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 48dp 属于大尺寸，比例本身已经让出现/消失看起来自然，
        // 不需要再加缩放（加了反而像"弹出来"）。这里只用淡入。
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(140L).start()

        overlay = box
        indicator = ind
        shownAt = System.currentTimeMillis()
    }

    /** 强制立即撤下（离线层接管时用），不做最短展示时间限制 */
    fun forceDismiss() {
        synchronized(this) {
            visibleCount = 0
            silentDepth = 0
            pendingHide?.let { host?.window?.decorView?.removeCallbacks(it) }
            pendingHide = null
        }
        val a = host
        if (a != null) a.runOnUiThread { detachNow() } else detachNow()
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
        box.animate().alpha(0f).setDuration(120L).withEndAction {
            ind?.hide()
            parent?.removeView(box)
        }.start()
    }
}