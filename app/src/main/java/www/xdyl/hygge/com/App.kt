package www.xdyl.hygge.com

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 自动恢复 WiFi ADB 配置
        val prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)
        if (prefs.getBoolean("wifi_adb_enabled", false)) {
            val savedIp = prefs.getString("wifi_adb_ip", "")
            if (!savedIp.isNullOrBlank()) {
                LogManager.deviceIp = savedIp
                LogManager.devicePort = prefs.getInt("wifi_adb_port", 5555)
                LogManager.wifiAdbEnabled = true
            }
        }
        LogManager.log("Application started")
    }
}
