package www.xdyl.hygge.com

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 圆形进度环：给迷你播放条的圆形按钮用（QQ 音乐那种，进度绕按钮外圈转）。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val strokePx = 2.5f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0x33FFFFFF
    }

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0xD9FFFFFF
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = strokePx / 2f + 1f
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, -90f, 360f, false, bgPaint)
        if (progress > 0f) {
            canvas.drawArc(rect, -90f, 360f * progress, false, fgPaint)
        }
    }
}