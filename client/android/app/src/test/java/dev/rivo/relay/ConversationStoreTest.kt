package dev.rivo.relay

import dev.rivo.relay.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationStoreTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun draftsSurviveReopenWithoutCrossingNodeOrThread() = runBlocking {
        val root = directory.root
        val path = ConversationStore.nodeDirectory(root, "node/device/base")
        val store = ConversationStore(path)
        val reference = JSONObject().put("type", "mention").put("name", "report.pdf").put("path", "/workspace/report.pdf")
        store.saveDraft("one", ConversationDraft("unfinished", listOf(StoredDraftImage("image", byteArrayOf(1, 2, 3))), listOf(reference), "model", "high", "plan"))
        store.putMetadata("selectedProjectId", "project")
        val restored = ConversationStore(path)
        val draft = restored.loadDraft("one")
        assertEquals("unfinished", draft.text)
        assertArrayEquals(byteArrayOf(1, 2, 3), draft.images.single().bytes)
        assertEquals("/workspace/report.pdf", draft.files.single().getString("path"))
        assertEquals("plan", draft.mode)
        assertEquals("model", draft.model)
        assertEquals("project", restored.getMetadata("selectedProjectId"))
        assertTrue(restored.loadDraft("two").empty)
        assertTrue(ConversationStore(ConversationStore.nodeDirectory(root, "different-node")).loadDraft("one").empty)
        restored.saveDraft("one", ConversationDraft())
        assertTrue(ConversationStore(path).loadDraft("one").empty)
    }
    @Test fun snapshotCacheIsBoundedAndRejectsWrongThread() = runBlocking {
        val store = ConversationStore(directory.root)
        repeat(14) { index ->
            val turns = JSONArray((0..39).map { JSONObject().put("id", "turn-$it").put("items", JSONArray()) })
            store.saveSnapshot("thread-$index", JSONObject().put("thread", JSONObject().put("id", "thread-$index").put("turns", turns)))
        }
        assertEquals(12, directory.root.listFiles()!!.count { it.name.startsWith("snapshot-") })
        assertEquals(30, store.loadSnapshot("thread-13")!!.getJSONObject("thread").getJSONArray("turns").length())
        store.saveSnapshot("wrong", JSONObject().put("thread", JSONObject().put("id", "elsewhere")))
        assertNull(store.loadSnapshot("wrong"))
    }
    @Test fun corruptRecoveryDataIsIgnored() = runBlocking {
        val store = ConversationStore(directory.root)
        store.saveDraft("thread", ConversationDraft("text"))
        directory.root.listFiles()!!.first { it.name.startsWith("draft-") }.writeText("{broken")
        assertTrue(store.loadDraft("thread").empty)
        File(directory.root, "metadata.json").writeText("not json")
        assertNull(ConversationStore(directory.root).getMetadata("read:thread"))
    }
}
