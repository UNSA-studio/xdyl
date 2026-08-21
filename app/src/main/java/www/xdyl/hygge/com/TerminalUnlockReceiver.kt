package www.xdyl.hygge.com

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.security.MessageDigest

 /**
  * 隐藏终端动态挑战解锁。
  *
  * 第一步（申请挑战码，写入公共目录）：
  *   adb shell am broadcast -n www.xdyl.hygge.com/.TerminalUnlockReceiver \
  *     -a www.xdyl.hygge.com.REQ_TERMINAL_CHALLENGE
  *   挑战码文件位置（按权限自动选择）：
 *     /sdcard/NebulaUpdater/nebula_terminal_challenge.txt
 *     或应用外部目录 / 内部目录同名文件
 *   广播后用 adb logcat -s NebulaUpdater 查看实际写入位置
  *
  * 第二步（携带挑战码和应答码解锁）：
  *   adb shell am broadcast -n www.xdyl.hygge.com/.TerminalUnlockReceiver \
  *     -a www.xdyl.hygge.com.ENABLE_TERMINAL \
  *     --es c "挑战码" --es r "应答码"
  *
  * 挑战码 30 分钟后过期。
  */
 class TerminalUnlockReceiver : BroadcastReceiver() {
     override fun onReceive(context: Context, intent: Intent) {
         val action = intent.action ?: return

         if (action == "www.xdyl.hygge.com.REQ_TERMINAL_CHALLENGE") {
            val challenge = randomHex()
            val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("terminal_challenge", challenge)
                .putLong("terminal_challenge_expire", System.currentTimeMillis() + 30 * 60 * 1000L)
                .commit()

            // 按权限从高到低尝试写入，成功即停
            val targets = listOf(
                File("/sdcard/NebulaUpdater/nebula_terminal_challenge.txt"),
                context.getExternalFilesDir(null)?.let { File(it, "nebula_terminal_challenge.txt") },
                File(context.filesDir, "nebula_terminal_challenge.txt")
            ).filterNotNull()
            for (f in targets) {
                try {
                    f.parentFile?.mkdirs()
                    f.writeText(challenge)
                    Log.i("NebulaUpdater", "挑战码已写入: ${f.absolutePath}")
                    return
                } catch (_: Exception) {}
            }
            return
        }

         if (action == "www.xdyl.hygge.com.ENABLE_TERMINAL") {
             val c = intent.getStringExtra("c") ?: return
             val r = intent.getStringExtra("r") ?: return

             val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
             val savedChallenge = prefs.getString("terminal_challenge", null) ?: return
             val expireAt = prefs.getLong("terminal_challenge_expire", 0L)

             if (c != savedChallenge || System.currentTimeMillis() > expireAt) {
                 Log.e("NebulaUpdaterTerminalUnlock", "terminal unlock rejected")
                 return
             }

             val expectAnswer = sha256(c + context.packageName + internalSalt())
             if (r != expectAnswer) {
                 Log.e("NebulaUpdaterTerminalUnlock", "terminal unlock rejected")
                 return
             }

             val sig = sha256(internalSalt2() + context.packageName)
             prefs.edit()
                 .putBoolean("terminal_enabled", true)
                 .putString("terminal_sig", sig)
                 .remove("terminal_challenge")
                 .remove("terminal_challenge_expire")
                 .commit()

             listOf(
                File("/sdcard/NebulaUpdater/nebula_terminal_challenge.txt"),
                context.getExternalFilesDir(null)?.let { File(it, "nebula_terminal_challenge.txt") },
                File(context.filesDir, "nebula_terminal_challenge.txt")
            ).filterNotNull().forEach { it.delete() }
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

     private fun randomHex(): String {
         val chars = "0123456789abcdef"
         val bytes = ByteArray(32)
         SecureRandom().nextBytes(bytes)
         return bytes.joinToString("") { chars[(it.toInt() and 0xFF) % 16].toString() }
     }
 }
