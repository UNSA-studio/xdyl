package www.xdyl.hygge.com

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ChatStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private val username = AtomicReference("alice")
    private val relay = AtomicReference("https://relay.example")

    @Before fun setup() {
        username.set("alice")
        relay.set("https://relay.example")
        context = Mockito.mock(Context::class.java)
        val settings = Mockito.mock(SharedPreferences::class.java)
        val session = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(context.filesDir).thenReturn(temp.root)
        Mockito.`when`(context.getSharedPreferences("xdyl_settings", Context.MODE_PRIVATE)).thenReturn(settings)
        Mockito.`when`(context.getSharedPreferences("starwave_session", Context.MODE_PRIVATE)).thenReturn(session)
        Mockito.`when`(session.getString("access_token", "")).thenReturn("test-session")
        Mockito.`when`(settings.getString("my_player_name", "")).thenAnswer { username.get() }
        Mockito.`when`(settings.getString(ChatApi.PREF_SERVER, "")).thenAnswer { relay.get() }
    }

    private fun owner() = ChatIdentity.current(context)!!
    private fun message(id: String = "m1") = ChatStore.Message(id, "bob", "alice", "hello", 100, false)

    @Test fun accountsDoNotShareHistoryIndexCursorOrPendingCiphertext() {
        val alice = owner()
        assertTrue(ChatStore.appendMessage(context, "bob", message(), alice))
        assertTrue(ChatStore.saveCursor(context, alice, 500))
        assertTrue(ChatStore.defer(context, alice, JSONObject().put("from", "bob").put("msgId", "pending").put("cipher", "opaque")))
        username.set("charlie")
        val charlie = owner()
        assertNotEquals(alice.scope, charlie.scope)
        assertTrue(ChatStore.loadMessages(context, "bob").isEmpty())
        assertTrue(ChatStore.loadIndex(context).isEmpty())
        assertEquals(0L, ChatStore.cursor(context, charlie))
        assertTrue(ChatStore.pending(context, charlie).isEmpty())
        assertEquals(1, ChatStore.loadMessages(context, "bob", alice).size)
        assertEquals(500L, ChatStore.cursor(context, alice))
    }

    @Test fun sameUsernameOnDifferentRelayUsesDifferentStorage() {
        val first = owner()
        assertTrue(ChatStore.appendMessage(context, "bob", message(), first))
        relay.set("https://other-relay.example")
        assertNotEquals(first.scope, owner().scope)
        assertTrue(ChatStore.loadIndex(context).isEmpty())
    }

    @Test fun replayIsIdempotentAndDoesNotIncreaseUnreadCountTwice() {
        assertTrue(ChatStore.appendMessage(context, "bob", message()))
        assertTrue(ChatStore.appendMessage(context, "bob", message()))
        assertEquals(1, ChatStore.loadMessages(context, "bob").size)
        assertEquals(1, ChatStore.loadIndex(context).single().unread)
    }

    @Test fun failedCiphertextRemainsDurableUntilExplicitlyDelivered() {
        val alice = owner()
        val cipher = JSONObject().put("from", "bob").put("to", "alice").put("msgId", "deferred").put("seq", 7).put("cipher", "opaque")
        assertTrue(ChatStore.defer(context, alice, cipher))
        assertTrue(ChatStore.defer(context, alice, cipher))
        assertTrue(ChatStore.saveCursor(context, alice, 7))
        assertEquals(1, ChatStore.pending(context, alice).size)
        assertEquals("opaque", ChatStore.pending(context, alice).single().getString("cipher"))
        assertTrue(ChatStore.appendMessage(context, "bob", message("deferred"), alice))
        ChatStore.removePending(context, alice, cipher)
        assertTrue(ChatStore.pending(context, alice).isEmpty())
        assertEquals(1, ChatStore.loadMessages(context, "bob", alice).size)
    }

    @Test fun legacyMigrationChecksOwnershipAndNeverDeletesLegacyFile() {
        val legacy = File(temp.root, "chat").apply { mkdirs() }
        val old = File(legacy, "bob.json")
        old.writeText(JSONArray().put(JSONObject().put("id", "legacy").put("from", "bob").put("to", "alice")
            .put("text", "old history").put("time", 1).put("dir", "in").put("state", "delivered")).toString())
        username.set("charlie")
        assertTrue(ChatStore.loadMessages(context, "bob").isEmpty())
        username.set("alice")
        assertEquals("old history", ChatStore.loadMessages(context, "bob").single().text)
        assertTrue(old.isFile)
        assertEquals(0L, ChatStore.cursor(context, owner()))
    }

    @Test fun backgroundResultUsesCapturedOwnerAfterAccountSwitch() {
        val alice = owner()
        username.set("charlie")
        assertTrue(ChatStore.appendMessage(context, "bob", message(), alice))
        assertTrue(ChatStore.loadMessages(context, "bob").isEmpty())
        assertEquals(1, ChatStore.loadMessages(context, "bob", alice).size)
    }

    @Test fun concurrentIncomingWritesDoNotOverwriteEachOther() {
        val alice = owner()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 40).map { n -> workers.submit<Boolean> {
                ChatStore.appendMessage(context, "bob", message("parallel-$n"), alice)
            } }
            futures.forEach { assertTrue(it.get(15, TimeUnit.SECONDS)) }
            assertEquals(40, ChatStore.loadMessages(context, "bob", alice).size)
            assertEquals(40, ChatStore.loadIndex(context, alice).single().unread)
        } finally { workers.shutdownNow() }
    }
}