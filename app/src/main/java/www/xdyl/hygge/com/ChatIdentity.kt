package www.xdyl.hygge.com

import android.content.Context
import java.security.MessageDigest

/** Stable storage boundary: relay server + authenticated account's canonical username. */
object ChatIdentity {
    data class Snapshot(val server: String, val username: String, val scope: String)

    fun scopeKey(server: String, username: String): String = MessageDigest.getInstance("SHA-256")
        .digest((server.trim().trimEnd('/') + "\n" + username).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun current(context: Context): Snapshot? {
        if (!SessionStore(context).isLoggedIn) return null
        val name = context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)
            .getString("my_player_name", "").orEmpty().trim()
        if (name.isEmpty()) return null
        val server = ChatApi.serverBase(context)
        return Snapshot(server, name, scopeKey(server, name))
    }

    fun isCurrent(context: Context, owner: Snapshot): Boolean = current(context)?.scope == owner.scope
}