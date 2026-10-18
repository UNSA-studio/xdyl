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
        val from: Int,
        val to: Int,
        val text: String,
        val time: Long,
        val outgoing: Boolean,
        var state: String = if (outgoing) "pending" else "delivered"
    )

    data class Peer(
        val userId: Int,
        val nickname: String,
        val lastText: String,
        val lastTime: Long,
        val unread: Int
    )

    private fun root(context: Context): File {
        val base = File(Environment.getExternalStorageDirectory(), "NebulaUpdater/chat")
        if (!base.exists()) base.mkdirs()
        return base
    }

    private fun peerFile(context: Context, peerId: Int) = File(root(context), "$peerId.json")
    private fun indexFile(context: Context) = File(root(context), "index.json")

    // ==================== 消息读写 ====================

    fun loadMessages(context: Context, peerId: Int): MutableList<Message> {
        val f = peerFile(context, peerId)
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = mutableListOf<Message>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Message(
                        o.optString("id"),
                        o.optInt("from"),
                        o.optInt("to"),
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
    fun appendMessage(context: Context, peerId: Int, msg: Message) {
        val list = loadMessages(context, peerId)
        val idx = list.indexOfFirst { it.id == msg.id }
        if (idx >= 0) list[idx] = msg else list.add(msg)
        list.sortBy { it.time }
        saveMessages(context, peerId, list)
        updateIndex(context, peerId, msg)
    }

    fun updateMessageState(context: Context, peerId: Int, msgId: String, state: String) {
        val list = loadMessages(context, peerId)
        val m = list.firstOrNull { it.id == msgId } ?: return
        m.state = state
        saveMessages(context, peerId, list)
    }

    private fun saveMessages(context: Context, peerId: Int, list: List<Message>) {
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
            peerFile(context, peerId).writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            LogManager.log("[CHAT] 保存失败: ${e.message}")
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
                        o.optInt("userId"),
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

    private fun updateIndex(context: Context, peerId: Int, msg: Message) {
        val index = loadIndex(context)
        val existing = index.firstOrNull { it.userId == peerId }
        val unread = if (msg.outgoing) 0 else (existing?.unread ?: 0) + 1
        val updated = Peer(
            peerId,
            existing?.nickname ?: "用户$peerId",
            msg.text,
            msg.time,
            unread
        )
        index.removeAll { it.userId == peerId }
        index.add(updated)
        saveIndex(context, index)
    }

    fun setPeerNickname(context: Context, peerId: Int, nickname: String) {
        val index = loadIndex(context)
        val idx = index.indexOfFirst { it.userId == peerId }
        if (idx >= 0) {
            val p = index[idx]
            index[idx] = p.copy(nickname = nickname)
            saveIndex(context, index)
        } else {
            index.add(Peer(peerId, nickname, "", System.currentTimeMillis(), 0))
            saveIndex(context, index)
        }
    }

    fun clearUnread(context: Context, peerId: Int) {
        val index = loadIndex(context)
        val idx = index.indexOfFirst { it.userId == peerId }
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
                    .put("userId", p.userId)
                    .put("nickname", p.nickname)
                    .put("lastText", p.lastText)
                    .put("lastTime", p.lastTime)
                    .put("unread", p.unread)
            )
        }
        try {
            indexFile(context).writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            LogManager.log("[CHAT] 索引保存失败: ${e.message}")
        }
    }

    /** 生成消息 id */
    fun newMessageId(): String = "${System.currentTimeMillis()}-${(1000..9999).random()}"
}