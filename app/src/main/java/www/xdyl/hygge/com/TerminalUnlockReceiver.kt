package www.xdyl.hygge.com

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 隐藏终端解锁接收器。
 * 通过 adb 手动打开：
 *   adb shell am broadcast -a www.xdyl.hygge.com.ENABLE_TERMINAL
 * 发送后扩展页面会出现"进入终端"按钮。
 */
class TerminalUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "www.xdyl.hygge.com.ENABLE_TERMINAL") {
            context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("terminal_enabled", true)
                .commit()
            Log.i("NebulaUpdater", "隐藏终端已通过 ADB 广播解锁")
            LogManager.log("隐藏终端已通过 ADB 广播解锁")
        }
    }
}
