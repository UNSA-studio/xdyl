package www.xdyl.hygge.com
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.security.MessageDigest

/**
 * 隐藏终端解锁接收器。
 * 必须携带正确密钥的广播才会解锁：
 *   adb shell am broadcast -n www.xdyl.hygge.com/.TerminalUnlockReceiver \
 *     -a www.xdyl.hygge.com.ENABLE_TERMINAL --es key "US.Kx9Qm2p"
 * 解锁时会同时写入校验签名，单纯修改 SharedPreferences 的
 * terminal_enabled=true 不会生效。
 */
class TerminalUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "www.xdyl.hygge.com.ENABLE_TERMINAL") {
            val key = intent.getStringExtra("key")
            val expect = "US.Kx9Qm2p"
            if (key != expect) {
                Log.i("NebulaUpdater", "隐藏终端解锁被拒绝：密钥无效")
                LogManager.log("隐藏终端解锁被拒绝：密钥无效")
                return
            }
            val sig = sha256(expect + context.packageName)
            context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("terminal_enabled", true)
                .putString("terminal_sig", sig)
                .commit()
            Log.i("NebulaUpdater", "隐藏终端已通过 ADB 广播解锁")
            LogManager.log("隐藏终端已通过 ADB 广播解锁")
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

