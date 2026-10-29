package www.xdyl.hygge.com

import android.content.Context
import android.content.SharedPreferences

/** 会话令牌存储（Android 侧等价 iOS 的 Keychain 策略：只存令牌与昵称，不存密码） */
class SessionStore(private val context: Context) {
    companion object { private val sessionLock = Any() }
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

    val sessionId: String get() = prefs.getString("session_id", "").orEmpty()

    fun saveSession(access: String, refresh: String, username: String, preserveIdentity: Boolean = false) = synchronized(sessionLock) {
        val id = if (preserveIdentity) sessionId else java.util.UUID.randomUUID().toString()
        if (!preserveIdentity) clearChatIdentity()
        prefs.edit().putString("access_token", access).putString("refresh_token", refresh)
            .putString("username", username).putString("session_id", id).apply()
    }

    fun applyRefreshedTokens(expectedSession: String, expectedRefresh: String, access: String, refresh: String): Boolean = synchronized(sessionLock) {
        if (sessionId != expectedSession || !isLoggedIn) false
        else if (refreshToken != expectedRefresh) true // Another request refreshed this same session already.
        else { saveSession(access, refresh, username, preserveIdentity = true); true }
    }

    private fun clearChatIdentity() {
        context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE).edit()
            .remove("my_player_name").remove("my_user_id").apply()
        ChatPage.resetForAccountChange()
    }

    fun logout() = synchronized(sessionLock) {
        prefs.edit().clear().apply()
        clearChatIdentity()
    }
}