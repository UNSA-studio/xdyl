package www.xdyl.hygge.com

import android.content.Context
import android.content.SharedPreferences

/** 会话令牌存储（Android 侧等价 iOS 的 Keychain 策略：只存令牌与昵称，不存密码） */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("starwave_session", Context.MODE_PRIVATE)

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        private set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        private set(value) = prefs.edit().putString("refresh_token", value).apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        private set(value) = prefs.edit().putString("username", value).apply()

    val isLoggedIn: Boolean get() = accessToken.isNotBlank()

    fun saveSession(access: String, refresh: String, username: String) {
        accessToken = access
        refreshToken = refresh
        this.username = username
    }

    fun logout() {
        prefs.edit().clear().apply()
    }
}