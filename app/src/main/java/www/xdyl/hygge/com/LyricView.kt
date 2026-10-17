package www.xdyl.hygge.com

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 【歌词视图（Canvas 自绘）】
 *
 * 为什么自绘：TextView + Spannable 只能按“字符”染色，天然一格一格。
 * 自绘可以按【像素宽度】裁剪已唱区域，得到真正平滑的“流动”效果，
 * 与 QQ音乐/网易云 等专业播放器一致。
 *
 * 用法：
 * ```
 * lyricView.lines = LrcParser.parseFile(lrc)   // 载入歌词
 * lyricView.update(positionMs)                 // 每帧调用（Choreographer 驱动）
 * ```
 */
class LyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 歌词行（设置后自动重置状态） */
    var lines: List<LrcParser.Line> = emptyList()
        set(value) {
            field = value
            currentIdx = -1
            activeProgress = 0f
            scrollY = 0f
            targetScrollY = 0f
            invalidate()
        }

    private var currentIdx = -1
    private var activeProgress = 0f
    private var scrollY = 0f
    private var targetScrollY = 0f

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val transPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }

    private val sungColor = Color.parseColor("#87D9FF")
    private val unsungColor = Color.parseColor("#4A4A4A")
    private val idleColor = Color.parseColor("#888888")

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * resources.displayMetrics.density

    /**
     * 根据播放位置更新（每帧调用）
     * @param positionMs 当前播放位置（毫秒）
     */
    fun update(positionMs: Int) {
        if (lines.isEmpty()) return

        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        currentIdx = idx

        // 行内进度：本行开始 → 下一行开始
        if (idx >= 0) {
            val startMs = lines[idx].timeMs
            val endMs = if (idx + 1 < lines.size) lines[idx + 1].timeMs else startMs + 4000L
            val span = (endMs - startMs).coerceAtLeast(200L)
            activeProgress = ((positionMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        } else {
            activeProgress = 0f
        }

        // 目标滚动：让当前行停在视图中间
        targetScrollY = if (idx >= 0) {
            rowTop(idx) + rowHeight(idx) / 2f - height / 2f
        } else 0f

        invalidate()
    }

    /** 某行的顶部 Y（未滚动坐标） */
    private fun rowTop(index: Int): Float {
        var y = 0f
        for (i in 0 until index) y += rowHeight(i)
        return y
    }

    private fun rowHeight(index: Int): Float {
        return if (lines[index].translation != null) dp(56f) else dp(38f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) {
            textPaint.color = idleColor
            textPaint.textSize = sp(13f)
            canvas.drawText("暂无歌词", width / 2f, height / 2f, textPaint)
            return
        }

        // 平滑滚动（每帧逼近目标，天然插值）
        if (abs(targetScrollY - scrollY) > 0.5f) {
            scrollY += (targetScrollY - scrollY) * 0.2f
            postInvalidateOnAnimation()
        } else {
            scrollY = targetScrollY
        }

        val cx = width / 2f
        var y = -scrollY + dp(8f)

        for (i in lines.indices) {
            val line = lines[i]
            val h = rowHeight(i)
            val baseline = y + h / 2f + sp(15f) * 0.35f
            val dist = if (currentIdx < 0) 99 else abs(i - currentIdx)

            // 离当前行越远越淡
            val alpha = when {
                i == currentIdx -> 255
                dist == 1 -> 155
                dist == 2 -> 100
                else -> 55
            }

            // 只绘制视口范围内的行（性能）
            if (baseline > -h && baseline < height + h) {
                if (i == currentIdx) {
                    // 当前行：先整行暗色，再按像素宽度裁剪亮色（流动效果）
                    textPaint.textSize = sp(17f)
                    textPaint.color = unsungColor
                    textPaint.alpha = 255
                    canvas.drawText(line.text, cx, baseline, textPaint)

                    val w = textPaint.measureText(line.text)
                    val clipW = w * activeProgress
                    if (clipW > 0f) {
                        canvas.save()
                        canvas.clipRect(cx - w / 2f, baseline - textPaint.textSize * 1.6f,
                            cx - w / 2f + clipW, baseline + textPaint.textSize * 0.5f)
                        textPaint.color = sungColor
                        canvas.drawText(line.text, cx, baseline, textPaint)
                        canvas.restore()
                    }

                    // 译文（不参与填充）
                    line.translation?.let { tr ->
                        transPaint.color = Color.parseColor("#9AA9BD")
                        transPaint.alpha = 220
                        canvas.drawText(tr, cx, baseline + sp(17f), transPaint)
                    }
                } else {
                    textPaint.textSize = sp(15f)
                    // 已经唱过的行保持“已唱色”（略淡），未唱的行才是灰色
                    val sung = i < currentIdx
                    textPaint.color = if (sung) Color.parseColor("#6FB6DC") else idleColor
                    textPaint.alpha = if (sung) 190 else alpha
                    canvas.drawText(line.text, cx, baseline, textPaint)

                    line.translation?.let { tr ->
                        transPaint.color = idleColor
                        transPaint.alpha = (alpha * 0.85f).toInt()
                        canvas.drawText(tr, cx, baseline + sp(13f), transPaint)
                    }
                }
            }
            y += h
        }
    }
}