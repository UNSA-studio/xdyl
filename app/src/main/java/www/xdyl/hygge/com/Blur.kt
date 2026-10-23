package www.xdyl.hygge.com

import android.graphics.Bitmap
import kotlin.math.max

/**
 * 背景模糊：先缩到极小尺寸做多次 box blur，再放大回目标尺寸。
 * 这样既快又柔和（放大时的双线性插值天然平滑），效果接近高斯模糊。
 */
object Blur {

    fun blurForBackground(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val w = max(targetW, 1)
        val h = max(targetH, 1)
        val smallW = 48
        val ratio = smallW.toFloat() / src.width.toFloat()
        val smallH = max(4, (src.height * ratio).toInt())
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val pix = IntArray(smallW * smallH)
        small.getPixels(pix, 0, smallW, 0, 0, smallW, smallH)
        repeat(3) { boxBlur(pix, smallW, smallH, 4) }
        val tmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        tmp.setPixels(pix, 0, smallW, 0, 0, smallW, smallH)
        return Bitmap.createScaledBitmap(tmp, w, h, true)
    }

    private fun boxBlur(pix: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val div = 2 * r + 1
        val tmp = IntArray(w * h)

        // 水平
        for (y in 0 until h) {
            val base = y * w
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val x = i.coerceIn(0, w - 1)
                val c = pix[base + x]
                sa += (c ushr 24) and 0xFF
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (x in 0 until w) {
                tmp[base + x] = ((sa / div) shl 24) or
                        ((sr / div) shl 16) or
                        ((sg / div) shl 8) or
                        (sb / div)
                val outX = (x - r).coerceIn(0, w - 1)
                val inX = (x + r + 1).coerceIn(0, w - 1)
                val co = pix[base + outX]
                val ci = pix[base + inX]
                sa += ((ci ushr 24) and 0xFF) - ((co ushr 24) and 0xFF)
                sr += ((ci shr 16) and 0xFF) - ((co shr 16) and 0xFF)
                sg += ((ci shr 8) and 0xFF) - ((co shr 8) and 0xFF)
                sb += (ci and 0xFF) - (co and 0xFF)
            }
        }

        // 垂直
        for (x in 0 until w) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val y = i.coerceIn(0, h - 1)
                val c = tmp[y * w + x]
                sa += (c ushr 24) and 0xFF
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (y in 0 until h) {
                pix[y * w + x] = ((sa / div) shl 24) or
                        ((sr / div) shl 16) or
                        ((sg / div) shl 8) or
                        (sb / div)
                val outY = (y - r).coerceIn(0, h - 1)
                val inY = (y + r + 1).coerceIn(0, h - 1)
                val co = tmp[outY * w + x]
                val ci = tmp[inY * w + x]
                sa += ((ci ushr 24) and 0xFF) - ((co ushr 24) and 0xFF)
                sr += ((ci shr 16) and 0xFF) - ((co shr 16) and 0xFF)
                sg += ((ci shr 8) and 0xFF) - ((co shr 8) and 0xFF)
                sb += (ci and 0xFF) - (co and 0xFF)
            }
        }
    }
}