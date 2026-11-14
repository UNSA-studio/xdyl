package www.xdyl.hygge.com

import android.app.Application

class App : Application() {

    companion object {
        /**
         * 本次进程启动时刻。
         *
         * 用来判定"这条 pending 消息是不是上一次进程遗留的"：
         * 发消息途中应用被杀，本地记录会永远停在 pending，
         * 重启后需要据此把它捡起来重发，否则 spinner 会一直转。
         */
        var startTime: Long = System.currentTimeMillis()
            private set

        /** 全局 Application 引用（供 object/工具类取 Context 用） */
        lateinit var ctx: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        ctx = this
        // 全局加载指示器：所有网络请求自动亮起右上角形变 LoadingIndicator
        LoadingOverlay.install(this)
        OfflineOverlay.install(this)
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
