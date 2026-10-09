package www.xdyl.hygge.com

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * IPv4 优先 DNS。
 *
 * 背景：部分网络环境下，App 走 IPv6 连接 Cloudflare 边缘节点时，
 * TLS Client Hello 一发出去就被「Connection reset by peer」重置
 * （如 unsa-fdws.cc.cd 解析到 2606:4700:...）。
 * 因此把 DNS 结果排序为 IPv4 在前，优先走可用的 IPv4 链路。
 */
object NetDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        if (all.isEmpty()) return all
        return all.sortedByDescending { it is Inet4Address }
    }
}