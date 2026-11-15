package www.xdyl.hygge.com

import android.content.Context
import java.security.MessageDigest

/**
 * 设备登记（服务端：POST /chat/device/register）。
 *
 * 目标：新装机的应用第一次碰私聊时，向服务端报告"我是谁、我是什么系统"，
 * 服务端记录后返回该平台对应的私聊节点地址。之后所有私聊流量都走这个地址。
 *
 * 设备码不落盘 —— 由主站账号 ID 派生：
 *
 *     device_code = SHA-256("nu-device-v1" | user_id | platform)  → 64 hex
 *
 * 同一账号无论重装多少次，派生结果都一样，所以服务端查到登记过的记录
 * 就能直接秒回节点地址，不需要用户做任何事。
 *
 * 注意：既然码跟随账号派生，同一账号在两台手机上会算出同一个码。
 * 服务端的主键是 (subject, device_code)，重复登记只是刷新 last_seen，
 * 不会产生冲突。如果将来要区分"每台物理设备"，必须改为本地随机生成
 * 并落盘，但那样重装就会换码，与本机制的目的冲突 —— 两种语义不可兼得。
 */
object ChatDeviceRegistry {

    private const val PREF = "xdyl_settings"
    private const val KEY_REGISTERED = "chat_device_registered"

    /** 内置默认服务地址（bootstrap）。用户仍可在扩展设置里手动覆盖。 */
    const val DEFAULT_CHAT_BASE = "https://nu-pe-chat.cc.cd"

    /** 当前 App 版本名，登记时带给服务端便于统计 */
    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /**
     * 派生设备码。user_id 为主站账号数字 ID；拿不到（未登录/未刷新资料）返回 null。
     */
    fun deriveDeviceCode(userId: Long, platform: String = "android"): String? {
        if (userId <= 0) return null
        val material = "nu-device-v1|$userId|$platform"
        val d = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 本会话是否已经登记过（进程内存 + 落盘双标记，避免每次都打请求） */
    fun isRegistered(context: Context): Boolean {
        if (sRegisteredSession) return true
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_REGISTERED, false)
    }

    @Volatile private var sRegisteredSession = false

    /** 登出时清标记，让下一次登录重新登记 */
    fun reset(context: Context) {
        sRegisteredSession = false
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY_REGISTERED).apply()
    }

    /**
     * 登记。整个过程幂等：
     *   - 已登记 → 直接返回 true，不发请求；
     *   - 未登录或没有 user_id → 返回 false（调用方可以稍后再试）；
     *   - 网络失败 → 返回 false，不影响本地功能，下次再试。
     *
     * 成功后把服务端下发的 chat_base 写回 chat_server 偏好 ——
     * 之后所有请求都自动指向服务端指定节点。
     */
    fun ensureRegistered(context: Context): Boolean {
        if (isRegistered(context)) return true

        val session = SessionStore(context)
        if (!session.isLoggedIn) return false
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val userId = prefs.getInt("my_user_id", 0).toLong()
        val code = deriveDeviceCode(userId) ?: return false

        val r = ChatApi.callInternal(context, "POST", "/chat/device/register", org.json.JSONObject()
            .put("device_code", code)
            .put("platform", "android")
            .put("app_version", appVersion(context))
        ) ?: run {
            LogManager.w("[CHAT] 设备登记请求失败（网络）")
            return false
        }

        if (!r.optBoolean("ok", false)) {
            LogManager.w("[CHAT] 设备登记被拒: " + r.optString("error", "unknown"))
            return false
        }

        val data = r.optJSONObject("data")
        val base = data?.optString("chat_base", "")?.trim()?.trimEnd('/') ?: ""
        if (base.isNotBlank()) {
            // 服务端指定了节点 → 写回偏好；用户手动填写的地址会被覆盖，
            // 这是刻意的：服务端才是节点分配的权威。
            prefs.edit().putString(ChatApi.PREF_SERVER, base).apply()
        }

        prefs.edit().putBoolean(KEY_REGISTERED, true).apply()
        sRegisteredSession = true
        LogManager.log("[CHAT] 设备登记完成 platform=android base=$base")
        return true
    }
}