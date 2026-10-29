package www.xdyl.hygge.com

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Serialized, atomic account-scoped storage. Legacy data is copied only after ownership checks. */
object ChatStore {
    data class Message(val id: String, val from: String, val to: String, val text: String,
        val time: Long, val outgoing: Boolean, var state: String = if (outgoing) "pending" else "delivered")
    data class Peer(val peerKey: String, val nickname: String, val lastText: String, val lastTime: Long, val unread: Int)

    private fun base(context: Context): File = if (isPublicStorage(context))
        File(Environment.getExternalStorageDirectory(), "NebulaUpdater/chat") else File(context.filesDir, "chat")

    fun isPublicStorage(context: Context): Boolean = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
        .getBoolean("chat_storage_public", false)

    fun storagePath(context: Context): String = ChatIdentity.current(context)?.let { root(context, it).absolutePath }
        ?: File(base(context), "accounts").absolutePath

    private fun safeName(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    @Synchronized private fun root(context: Context, owner: ChatIdentity.Snapshot): File {
        require(owner.scope.matches(Regex("[a-f0-9]{64}")))
        val legacy = base(context)
        val dir = File(legacy, "accounts/${owner.scope}").apply { mkdirs() }
        val marker = File(dir, ".legacy-imported")
        if (!marker.exists()) {
            val imported = mutableListOf<Peer>()
            val oldIndex = runCatching { readIndex(File(legacy, "index.json")) }.getOrDefault(mutableListOf())
            legacy.listFiles().orEmpty().filter {
                it.isFile && it.extension == "json" && it.name != "index.json" && !it.name.endsWith(".keys.json")
            }.forEach { file ->
                val peer = file.nameWithoutExtension
                val messages = runCatching { readMessages(file) }.getOrNull() ?: return@forEach
                val owned = messages.isNotEmpty() && messages.all { m ->
                    if (m.outgoing) m.from == owner.username && m.to == peer
                    else m.to == owner.username && m.from == peer
                }
                if (owned) {
                    val dest = File(dir, safeName(peer) + ".json")
                    if (!dest.exists()) FileSafety.writeAtomic(dest, file.readText(Charsets.UTF_8))
                    val last = messages.maxByOrNull { it.time }!!
                    imported.add(oldIndex.firstOrNull { it.peerKey == peer }
                        ?: Peer(peer, peer, last.text, last.time, 0))
                }
            }
            val current = readIndex(File(dir, "index.json"))
            val merged = current + imported.filter { p -> current.none { it.peerKey == p.peerKey } }
            if (merged.isNotEmpty()) writeIndex(File(dir, "index.json"), merged)
            // Do not inherit the legacy global cursor; replay is idempotent and recovers skipped messages.
            FileSafety.writeAtomic(marker, "v1")
        }
        return dir
    }

    private fun readMessages(file: File): MutableList<Message> {
        if (!file.exists()) return mutableListOf()
        val arr = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Message(o.getString("id"), o.getString("from"), o.getString("to"), o.optString("text"),
                o.optLong("time"), o.optString("dir") == "out", o.optString("state", "delivered"))
        }.toMutableList()
    }

    private fun writeMessages(file: File, messages: List<Message>) {
        val arr = JSONArray()
        messages.forEach { m -> arr.put(JSONObject().put("id", m.id).put("from", m.from).put("to", m.to)
            .put("text", m.text).put("time", m.time).put("dir", if (m.outgoing) "out" else "in").put("state", m.state)) }
        FileSafety.writeAtomic(file, arr.toString())
    }

    private fun readIndex(file: File): MutableList<Peer> {
        if (!file.exists()) return mutableListOf()
        val arr = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Peer(o.getString("peerKey"), o.optString("nickname"), o.optString("lastText"), o.optLong("lastTime"), o.optInt("unread"))
        }.sortedByDescending { it.lastTime }.toMutableList()
    }

    private fun writeIndex(file: File, peers: List<Peer>) {
        val arr = JSONArray()
        peers.forEach { p -> arr.put(JSONObject().put("peerKey", p.peerKey).put("nickname", p.nickname)
            .put("lastText", p.lastText).put("lastTime", p.lastTime).put("unread", p.unread)) }
        FileSafety.writeAtomic(file, arr.toString())
    }

    @Synchronized fun loadMessages(context: Context, peerKey: String,
        owner: ChatIdentity.Snapshot? = ChatIdentity.current(context)): MutableList<Message> {
        if (owner == null) return mutableListOf()
        return try { readMessages(File(root(context, owner), safeName(peerKey) + ".json")) }
        catch (e: Exception) { LogManager.e("[CHAT] 记录读取失败（保留原文件）: ${e.message}"); mutableListOf() }
    }

    @Synchronized fun appendMessage(context: Context, peerKey: String, msg: Message,
        owner: ChatIdentity.Snapshot? = ChatIdentity.current(context)): Boolean {
        if (owner == null) return false
        return try {
            val dir = root(context, owner)
            val file = File(dir, safeName(peerKey) + ".json")
            val messages = readMessages(file) // Never overwrite an unreadable history with an empty list.
            val idx = messages.indexOfFirst { it.id == msg.id }
            if (idx >= 0) messages[idx] = msg else messages.add(msg)
            messages.sortBy { it.time }
            writeMessages(file, messages)
            val index = readIndex(File(dir, "index.json"))
            val previous = index.firstOrNull { it.peerKey == peerKey }
            val last = messages.lastOrNull() ?: msg
            val unread = if (idx >= 0) previous?.unread ?: 0 else if (msg.outgoing) 0 else (previous?.unread ?: 0) + 1
            index.removeAll { it.peerKey == peerKey }
            index.add(Peer(peerKey, previous?.nickname ?: peerKey, last.text, last.time, unread))
            writeIndex(File(dir, "index.json"), index)
            true
        } catch (e: Exception) { LogManager.e("[CHAT] 保存失败: ${e.message}"); false }
    }

    @Synchronized fun updateMessageState(context: Context, peerKey: String, id: String, state: String,
        owner: ChatIdentity.Snapshot? = ChatIdentity.current(context)): Boolean {
        if (owner == null) return false
        return try {
            val file = File(root(context, owner), safeName(peerKey) + ".json")
            val messages = readMessages(file)
            val msg = messages.firstOrNull { it.id == id } ?: return false
            msg.state = state
            writeMessages(file, messages)
            true
        } catch (e: Exception) { LogManager.e("[CHAT] 状态保存失败: ${e.message}"); false }
    }

    @Synchronized fun loadIndex(context: Context,
        owner: ChatIdentity.Snapshot? = ChatIdentity.current(context)): MutableList<Peer> {
        if (owner == null) return mutableListOf()
        return try { readIndex(File(root(context, owner), "index.json")) }
        catch (e: Exception) { LogManager.e("[CHAT] 索引读取失败: ${e.message}"); mutableListOf() }
    }

    @Synchronized fun setPeerNickname(context: Context, peerKey: String, nickname: String,
        owner: ChatIdentity.Snapshot? = ChatIdentity.current(context)) {
        if (owner == null) return
        try {
            val f = File(root(context, owner), "index.json")
            val index = readIndex(f)
            val old = index.firstOrNull { it.peerKey == peerKey }
            index.removeAll { it.peerKey == peerKey }
            index.add(old?.copy(nickname = nickname) ?: Peer(peerKey, nickname, "", System.currentTimeMillis(), 0))
            writeIndex(f, index)
        } catch (e: Exception) { LogManager.e("[CHAT] 昵称保存失败: ${e.message}") }
    }

    @Synchronized fun clearUnread(context: Context, peerKey: String,
        owner: ChatIdentity.Snapshot? = ChatIdentity.current(context)) {
        if (owner == null) return
        try {
            val f = File(root(context, owner), "index.json")
            writeIndex(f, readIndex(f).map { if (it.peerKey == peerKey) it.copy(unread = 0) else it })
        } catch (e: Exception) { LogManager.e("[CHAT] 未读数保存失败: ${e.message}") }
    }

    @Synchronized fun cursor(context: Context, owner: ChatIdentity.Snapshot): Long =
        File(root(context, owner), "cursor.txt").let { if (it.isFile) it.readText().toLongOrNull() ?: 0L else 0L }

    @Synchronized fun saveCursor(context: Context, owner: ChatIdentity.Snapshot, cursor: Long): Boolean = try {
        FileSafety.writeAtomic(File(root(context, owner), "cursor.txt"), cursor.toString()); true
    } catch (e: Exception) { LogManager.e("[CHAT] 游标保存失败: ${e.message}"); false }

    @Synchronized fun pending(context: Context, owner: ChatIdentity.Snapshot): List<JSONObject> {
        val f = File(root(context, owner), "pending-ciphertext.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun pendingId(o: JSONObject) = o.optString("from") + ":" + o.optString("msgId").ifBlank { "seq-${o.optLong("seq")}" }

    @Synchronized fun defer(context: Context, owner: ChatIdentity.Snapshot, message: JSONObject): Boolean = try {
        val all = pending(context, owner).filterNot { pendingId(it) == pendingId(message) } + message
        val arr = JSONArray(); all.forEach { arr.put(it) }
        FileSafety.writeAtomic(File(root(context, owner), "pending-ciphertext.json"), arr.toString()); true
    } catch (e: Exception) { LogManager.e("[CHAT] 待解密密文保存失败: ${e.message}"); false }

    @Synchronized fun removePending(context: Context, owner: ChatIdentity.Snapshot, message: JSONObject) {
        val file = File(root(context, owner), "pending-ciphertext.json")
        if (!file.isFile) return
        val before = pending(context, owner)
        val remaining = before.filterNot { pendingId(it) == pendingId(message) }
        if (remaining.size == before.size) return
        val arr = JSONArray(); remaining.forEach { arr.put(it) }
        FileSafety.writeAtomic(file, arr.toString())
    }

    fun newMessageId(): String = java.util.UUID.randomUUID().toString()
}
