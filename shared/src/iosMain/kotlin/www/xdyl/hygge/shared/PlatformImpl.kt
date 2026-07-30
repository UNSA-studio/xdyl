package www.xdyl.hygge.shared

import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.stringByAppendingPathComponent
import androidx.compose.ui.window.ComposeUIViewController

actual fun getLauncherRoot(): String = NSHomeDirectory()

actual fun getMinecraftDir(start: String): String? {
    val mcDir = (start as NSString).stringByAppendingPathComponent(".minecraft")
    val fileManager = NSFileManager.defaultManager
    if (fileManager.fileExistsAtPath(mcDir)) return mcDir
    val alt = (start as NSString).stringByAppendingPathComponent("minecraft")
    if (fileManager.fileExistsAtPath(alt)) return alt
    return null
}

actual fun installResourcePack(prefs: Preferences) {
    // 在 iOS 上实现材质包安装
}

actual class Preferences {
    private val userDefaults = platform.Foundation.NSUserDefaults.standardUserDefaults

    actual fun getString(key: String, default: String?): String? {
        return userDefaults.stringForKey(key) ?: default
    }
    actual fun getBoolean(key: String, default: Boolean): Boolean {
        return if (userDefaults.objectForKey(key) != null) userDefaults.boolForKey(key) else default
    }
    actual fun getInt(key: String, default: Int): Int {
        return if (userDefaults.objectForKey(key) != null) userDefaults.integerForKey(key).toInt() else default
    }
    actual fun putString(key: String, value: String) {
        userDefaults.setObject(value, forKey = key)
    }
    actual fun putBoolean(key: String, value: Boolean) {
        userDefaults.setBool(value, forKey = key)
    }
    actual fun putInt(key: String, value: Int) {
        userDefaults.setInteger(value.toLong(), forKey = key)
    }
    actual fun clear() {
        val domain = platform.Foundation.NSBundle.mainBundle.bundleIdentifier ?: ""
        userDefaults.removePersistentDomainForName(domain)
    }
}

fun MainViewController() = ComposeUIViewController { MainScreen() }
