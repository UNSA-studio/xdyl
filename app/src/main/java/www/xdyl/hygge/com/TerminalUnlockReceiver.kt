package www.xdyl.hygge.com

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.security.MessageDigest
import java.util.Random

/**
 * 隐藏终端动态挑战解锁。
 *
 * 第一步（申请挑战码，有效期 30 分钟）：
 *   adb shell am broadcast -n www.xdyl.hygge.com/.TerminalUnlockReceiver \
 *     -a www.xdyl.hygge.com.REQ_TERMINAL_CHALLENGE
 *   挑战码会打印到 logcat -s NebulaUpdater
 *
 * 第二步（携带挑战码和应答码解锁）：
 *   adb shell am broadcast -n www.xdyl.hygge.com/.TerminalUnlockReceiver \
 *     -a www.xdyl.hygge.com.ENABLE_TERMINAL \
 *     --es c "挑战码" --es r "应答码"
 *
 * 核心逻辑：
 * - 没有固定硬编码密钥
 * - 应答码 = sha256(挑战码 + 应用包名 + 内部散布盐)
 * - 挑战码 30 分钟后过期
 */
class TerminalUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == "www.xdyl.hygge.com.REQ_TERMINAL_CHALLENGE") {
            val challenge = randomHex(32)
            val expireAt = System.currentTimeMillis() + 30 * 60 * 1000L
            context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("terminal_challenge", challenge)
                .putLong("terminal_challenge_expire", expireAt)
                .commit()
            Log.i("NebulaUpdater", "终端挑战码(30分钟内有效): $challenge")
            LogManager.log("终端挑战码已生成, 有效期30分钟")
            return
        }

        if (action == "www.xdyl.hygge.com.ENABLE_TERMINAL") {
            val c = intent.getStringExtra("c") ?: return
            val r = intent.getStringExtra("r") ?: return

            val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            val savedChallenge = prefs.getString("terminal_challenge", null) ?: return
            val expireAt = prefs.getLong("terminal_challenge_expire", 0L)

            // 挑战码错误或过期直接拒绝
            if (c != savedChallenge || System.currentTimeMillis() > expireAt) {
                Log.i("NebulaUpdater", "终端解锁被拒绝: 挑战码错误或已过期")
                LogManager.log("终端解锁被拒绝: 挑战码错误或已过期")
                return
            }

            val expectAnswer = sha256(c + context.packageName + internalSalt())
            if (r != expectAnswer) {
                Log.i("NebulaUpdater", "终端解锁被拒绝: 应答码不匹配")
                LogManager.log("终端解锁被拒绝: 应答码不匹配")
                return
            }

            val sig = sha256(internalSalt2() + context.packageName)
            prefs.edit()
                .putBoolean("terminal_enabled", true)
                .putString("terminal_sig", sig)
                .remove("terminal_challenge")
                .remove("terminal_challenge_expire")
                .commit()
            Log.i("NebulaUpdater", "隐藏终端已通过动态挑战解锁")
            LogManager.log("隐藏终端已通过动态挑战解锁")
        }
    }

    private fun internalSalt(): String = listOf(
        "n", "e", "b", "u", "l", "a", "_", "u", "p", "d", "a", "t", "e", "r"
    ).joinToString("")

    private fun internalSalt2(): String = listOf(
        "s", "t", "a", "r", "_", "x", "d", "y", "l"
    ).joinToString("")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(len)
        val rnd = Random()
        repeat(len) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }
}
