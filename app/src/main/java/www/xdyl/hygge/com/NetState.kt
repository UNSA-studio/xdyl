package www.xdyl.hygge.com

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 网络可达性判断。
 *
 * 只用来回答两个问题：
 *  1. 这个异常是不是"连不上网/连不上服务器"（而不是服务端明确报错）；
 *  2. 当前设备是否真的在线。
 */
object NetState {

    /**
     * 是否属于"网络层失败"。
     *
     * 关键区分：服务端返回 code != 200（ApiException）属于业务错误，
     * 应该照常弹 ERROR 弹窗，**不能**被当成断网吞掉。
     */
    fun isNetworkFailure(e: Throwable?): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            // 服务端明确给出错误码 → 不是网络问题，直接排除
            if (t is ApiException) return false
            when (t) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                is NoRouteToHostException,
                is PortUnreachableException,
                is SSLException -> return true
            }
            val m = t.message ?: ""
            if (m.contains("Unable to resolve host", true) ||
                m.contains("Failed to connect", true) ||
                m.contains("Network is unreachable", true) ||
                m.contains("Connection refused", true) ||
                m.contains("connect timed out", true) ||
                m.contains("timeout", true) && !m.contains("refreshed", true)
            ) {
                return true
            }
            t = t.cause
            depth++
        }
        return false
    }

    /** 设备当前是否具备可用的互联网连接 */
    fun isOnline(ctx: Context = App.ctx): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val n = cm.activeNetwork ?: return false
            val c = cm.getNetworkCapabilities(n) ?: return false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (t: Throwable) {
            false
        }
    }
}
