package www.xdyl.hygge.com

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 【私聊本地存储】
 *
 * 目录：/sdcard/NebulaUpdater/chat/
 *   ├── index.json            会话索引（对端 id / 昵称 / 最后消息 / 未读数）
 *   ├── <peerId>.json         与某人的完整对话
 *   └── <peerId>.keys.json    与该对端的会话密钥材料（公钥等）
 *
 * 消息结构：
 * {
 *   "id": "1700000000000-abc",
 *   "from": 21, "to": 22,
 *   "text": "解密后的明文",
 *   "time": 1700000000000,
 *   "dir": "out" | "in",
 *   "state": "pending" | "sent" | "delivered" | "read" | "failed"
 * }
 */
object ChatStore {

    data class Message(
        val id: String,
        /** 发送者 username */
        val from: String,
        /** 接收者 username */
        val to: String,
        val text: String,
        val time: Long,
        val outgoing: Boolean,
        var state: String = if (outgoing) "pending" else "delivered"
    )

    data class Peer(
        /** 对端标识（username，如 OvalXD_chen）——主站 /player/info/{username} 的键 */
        val peerKey: String,
        val nickname: String,
        val lastText: String,
        val lastTime: Long,
        val unread: Int
    )

    /**
     * 存储目录
     *
     * 默认：App 私有目录 filesDir/chat（外部应用/ADB 普通权限都无法读取，防篡改）
     * 可选：公开目录 /sdcard/NebulaUpdater/chat（便于导出，需 ADB 开关打开）
     *       开关：adb broadcast -a www.xdyl.hygge.com.ENABLE_CHAT_PUBLIC ...
     */
    private fun root(context: Context): File {
        val prefs = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        val usePublic = prefs.getBoolean("chat_storage_public", false)
        val base = if (usePublic) {
            File(Environment.getExternalStorageDirectory(), "NebulaUpdater/chat")
        } else {
            File(context.filesDir, "chat")
        }
        if (!base.exists()) base.mkdirs()
        return base
    }

    /** 当前存储位置（用于设置页展示） */
    fun storagePath(context: Context): String = root(context).absolutePath

    fun isPublicStorage(context: Context): Boolean =
        context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .getBoolean("chat_storage_public", false)

    private fun peerFile(context: Context, peerKey: String) = File(root(context), safeName(peerKey) + ".json")
    /** 文件名安全化（username 只含字母数字下划线，这里兜底） */
    private fun safeName(key: String): String =
        key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun indexFile(context: Context) = File(root(context), "index.json")

    // ==================== 消息读写 ====================

    fun loadMessages(context: Context, peerKey: String): MutableList<Message> {
        val f = peerFile(context, peerKey)
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = mutableListOf<Message>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Message(
                        o.optString("id"),
                        o.optString("from"),
                        o.optString("to"),
                        o.optString("text"),
                        o.optLong("time"),
                        o.optString("dir") == "out",
                        o.optString("state", "delivered")
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** 追加一条消息（去重：同 id 覆盖） */
    fun appendMessage(context: Context, peerKey: String, msg: Message) {
        val list = loadMessages(context, peerKey)
        val idx = list.indexOfFirst { it.id == msg.id }
        if (idx >= 0) list[idx] = msg else list.add(msg)
        list.sortBy { it.time }
        saveMessages(context, peerKey, list)
        updateIndex(context, peerKey, msg)
    }

    fun updateMessageState(context: Context, peerKey: String, msgId: String, state: String) {
        val list = loadMessages(context, peerKey)
        val m = list.firstOrNull { it.id == msgId } ?: return
        m.state = state
        saveMessages(context, peerKey, list)
    }

    private fun saveMessages(context: Context, peerKey: String, list: List<Message>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("from", m.from)
                    .put("to", m.to)
                    .put("text", m.text)
                    .put("time", m.time)
                    .put("dir", if (m.outgoing) "out" else "in")
                    .put("state", m.state)
            )
        }
        try {
            peerFile(context, peerKey).writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            LogManager.e("[CHAT] 保存失败: ${e.message}")
        }
    }

    // ==================== 会话索引 ====================

    fun loadIndex(context: Context): MutableList<Peer> {
        val f = indexFile(context)
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = mutableListOf<Peer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Peer(
                        o.optString("peerKey"),
                        o.optString("nickname"),
                        o.optString("lastText"),
                        o.optLong("lastTime"),
                        o.optInt("unread", 0)
                    )
                )
            }
            list.sortedByDescending { it.lastTime }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun updateIndex(context: Context, peerKey: String, msg: Message) {
        val index = loadIndex(context)
        val existing = index.firstOrNull { it.peerKey == peerKey }
        val unread = if (msg.outgoing) 0 else (existing?.unread ?: 0) + 1
        val updated = Peer(
            peerKey,
            existing?.nickname ?: "用户$peerKey",
            msg.text,
            msg.time,
            unread
        )
        index.removeAll { it.peerKey == peerKey }
        index.add(updated)
        saveIndex(context, index)
    }

    fun setPeerNickname(context: Context, peerKey: String, nickname: String) {
        val index = loadIndex(context)
        val idx = index.indexOfFirst { it.peerKey == peerKey }
        if (idx >= 0) {
            val p = index[idx]
            index[idx] = p.copy(nickname = nickname)
            saveIndex(context, index)
        } else {
            index.add(Peer(peerKey, nickname, "", System.currentTimeMillis(), 0))
            saveIndex(context, index)
        }
    }

    fun clearUnread(context: Context, peerKey: String) {
        val index = loadIndex(context)
        val idx = index.indexOfFirst { it.peerKey == peerKey }
        if (idx >= 0) {
            index[idx] = index[idx].copy(unread = 0)
            saveIndex(context, index)
        }
    }

    private fun saveIndex(context: Context, list: List<Peer>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("peerKey", p.peerKey)
                    .put("nickname", p.nickname)
                    .put("lastText", p.lastText)
                    .put("lastTime", p.lastTime)
                    .put("unread", p.unread)
            )
        }
        try {
            indexFile(context).writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            LogManager.e("[CHAT] 索引保存失败: ${e.message}")
        }
    }

    /** 生成消息 id */
    fun newMessageId(): String = "${System.currentTimeMillis()}-${(1000..9999).random()}"
}