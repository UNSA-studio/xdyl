package www.xdyl.hygge.com

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView
import com.google.android.material.loadingindicator.LoadingIndicator

/**
 * M3 Expressive LoadingIndicator 的轻量用法。
 *
 * 官方 LoadingIndicator 是个 View，要占位就得额外套一层容器。
 * 它的 drawable 也能直接用，但 setVisible(3参) 是包内可见的，
 * 外部只能通过 View 的 show()/hide() 驱动动画。
 *
 * 所以这里走官方途径：内部持有一个 setBounds 到指定尺寸的 LoadingIndicator View，
 * 由它负责 show()/hide() 和动画，再画到 TextView 的 compound drawable 上。
 * 这样既不需要额外 FrameLayout，也不依赖非公开 API。
 */
object Spinner {

    /**
     * 在 TextView 右侧放一个转圈。文字自身不变，只在加载时调用。
     */
    fun showOnTextEnd(tv: TextView, sizeDp: Int = 16, rightDp: Int = 6) {
        val host = Host(tv.context, sizeDp, 0xFFA0C4FF.toInt())
        host.start()
        tv.setCompoundDrawablesRelative(null, null, host, null)
        tv.compoundDrawablePadding =
            (rightDp * tv.resources.displayMetrics.density).toInt()
    }

    fun hideOnText(tv: TextView) {
        (tv.compoundDrawablesRelative.getOrNull(2) as? Host)?.stop()
        tv.setCompoundDrawablesRelative(null, null, null, null)
    }

    /** 把 LoadingIndicator 包成 Drawable，尺寸固定，交给 View 自己跑动画 */
    private class Host(
        context: Context,
        sizeDp: Int,
        color: Int
    ) : Drawable() {
        private val px = (sizeDp * context.resources.displayMetrics.density).toInt()
        private val view = LoadingIndicator(context).apply {
            indicatorSize = px
            setIndicatorColor(color)
        }

        fun start() = view.show()

        fun stop() = view.hide()

        override fun draw(canvas: Canvas) {
            // 用 View 自己的测量/布局/绘制流程，动画由它内部驱动
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(px, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(px, android.view.View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, px, px)
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            view.draw(canvas)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            view.alpha = alpha / 255f
            invalidateSelf()
        }

        override fun setColorFilter(cf: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = px
        override fun getIntrinsicHeight(): Int = px
    }
}
