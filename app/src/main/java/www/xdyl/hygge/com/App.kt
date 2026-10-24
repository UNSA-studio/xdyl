package www.xdyl.hygge.com

import android.app.Application

class App : Application() {

    companion object {
        /** 全局 Application 引用（供 object/工具类取 Context 用） */
        lateinit var ctx: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        ctx = this
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
