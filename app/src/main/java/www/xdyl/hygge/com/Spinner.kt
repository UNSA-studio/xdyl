package www.xdyl.hygge.com

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.TextView
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.loadingindicator.LoadingIndicatorDrawable

/**
 * M3 Expressive LoadingIndicator 的轻量用法。
 *
 * 官方 LoadingIndicator 是个 View，要占位就得额外套容器。这里改用它的
 * LoadingIndicatorDrawable：本身就是一个会形变+旋转的 Drawable，
 * 可以当 TextView 的 compound drawable / 背景 / ImageView 的 src 用，
 * 不需要额外包一层 FrameLayout。
 *
 * 注意：必须 setVisible(true, true, true) 才会真正跑动画，
 * 只改 visibility 或只 draw 会得到一个静止的方块。
 */
object Spinner {

    fun createDrawable(c: Context, sizeDp: Int = 18, color: Int = 0xFFA0C4FF.toInt()): Drawable {
        val ind = LoadingIndicator(c)
        ind.indicatorSize = dp(c, sizeDp)
        ind.setIndicatorColor(color)
        val d = ind.drawable
        d.setVisible(true, true, true)
        return d
    }

    fun start(d: Drawable?) {
        d?.setVisible(true, true, true)
    }

    fun stop(d: Drawable?) {
        d?.setVisible(false, false, false)
    }

    /**
     * 在 TextView 右侧放一个转圈，rightDp 是右侧留白（避免压住文字）。
     * 文字本身不用改，只在加载时调用。
     */
    fun showOnTextEnd(tv: TextView, sizeDp: Int = 16, rightDp: Int = 4) {
        val d = createDrawable(tv.context, sizeDp)
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null,
            SizedDrawable(d, dp(tv.context, sizeDp)), null
        )
        tv.compoundDrawablePadding = dp(tv.context, rightDp)
        start(d)
    }

    fun hideOnText(tv: TextView) {
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
    }

    private fun dp(c: Context, v: Int): Int =
        (v * c.resources.displayMetrics.density).toInt()

    /** 把 Drawable 固定成指定像素尺寸，避免 intrinsic 尺寸为 -1 时不显示 */
    private class SizedDrawable(private val inner: Drawable, private val px: Int) : Drawable() {
        override fun draw(canvas: android.graphics.Canvas) {
            inner.setBounds(0, 0, px, px)
            inner.draw(canvas)
        }

        override fun setAlpha(alpha: Int) { inner.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { inner.colorFilter = cf }
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        override fun setVisible(visible: Boolean, restart: Boolean): Boolean =
            inner.setVisible(visible, restart, restart)

        override fun getIntrinsicWidth(): Int = px
        override fun getIntrinsicHeight(): Int = px
    }
}