package www.xdyl.hygge.shared

import android.os.Environment
import java.io.File

actual fun getLauncherRoot(): String = Environment.getExternalStorageDirectory().absolutePath
actual fun getMinecraftDir(start: String): String? {
    val mc = File(start, ".minecraft")
    return if (mc.exists()) mc.absolutePath else null
}
actual fun installResourcePack(prefs: Preferences) {
    // Android 实现，但实际调用在 app 模块中，此处留空
}

actual class Preferences {
    private val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(???)
    // 需要 Context，暂无法在此处实现，实际使用时需通过依赖注入
    actual fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    actual fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    actual fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    actual fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    actual fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    actual fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    actual fun clear() = prefs.edit().clear().apply()
}
