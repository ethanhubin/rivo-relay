package dev.rivo.relay

import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Collections

class ConversationStorePerformanceTest {
    @Test fun unreadableExistingMetadataCannotBeOverwrittenByANewCheckpoint() = runBlocking {
        val directory = Files.createTempDirectory("rivo-strict-metadata").toFile()
        try {
            val store = ConversationStore(directory)
            withContext(Dispatchers.IO) { store.checkpointPendingSubmission("project", "old", "create") }
            val file = java.io.File(directory, "metadata.json")
            file.writeText("{unreadable receipt bytes")
            val preserved = file.readBytes()
            var failed = false
            try { withContext(Dispatchers.IO) { store.checkpointPendingSubmission("project", "new", "send") } }
            catch (error: IllegalArgumentException) { failed = true; assertTrue(error.message!!.contains("手机恢复记录")) }
            assertTrue(failed)
            assertArrayEquals(preserved, file.readBytes())
            assertNotNull(store.getMetadata("pending-submission:old"))
            assertNull(store.getMetadata("pending-submission:new"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedDurableCheckpointPropagatesAndNeverPublishesAReceipt() = runBlocking {
        val blocked = Files.createTempDirectory("rivo-no-directory").toFile()
        try {
            val store = ConversationStore(blocked)
            assertTrue(blocked.delete())
            blocked.writeText("storage became unavailable after initialization")
            var failed = false
            try { withContext(Dispatchers.IO) { store.checkpointPendingSubmission("project", "request", "send") } }
            catch (error: IllegalArgumentException) { failed = true; assertTrue(error.message!!.contains("手机恢复记录")) }
            assertTrue(failed)
            assertNull(store.getMetadata("pending-submission:request"))
        } finally { blocked.delete() }
    }

    @Test fun navigationMarkersBatchOffCallerAndCannotEraseDurableReceiptFromAnotherStore() = runBlocking {
        val directory = Files.createTempDirectory("rivo-metadata-batch").toFile()
        val writes = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val caller = Thread.currentThread().id
        try {
            val first = ConversationStore(directory) { writes += it to Thread.currentThread().id }
            val second = ConversationStore(directory)
            repeat(100) { first.putMetadata("read:$it", "$it") }
            withContext(Dispatchers.IO) { second.checkpointPendingSubmission("project", "request", "create") }
            first.flushMetadata()
            val reopened = ConversationStore(directory)
            repeat(100) { assertEquals("$it", reopened.getMetadata("read:$it")) }
            assertNotNull(reopened.getMetadata("pending-submission:request"))
            assertEquals(1, writes.count { it.first == "metadata" })
            assertTrue(writes.all { it.second != caller })
        } finally { directory.deleteRecursively() }
    }

    @Test fun snapshotTrimsByUtf8AndSkipsIdenticalWritesWithoutDroppingSameRevisionUpdates() = runBlocking {
        val directory = Files.createTempDirectory("rivo-snapshot-batch").toFile()
        val writes = Collections.synchronizedList(mutableListOf<String>())
        try {
            val store = ConversationStore(directory) { writes += it }
            val turns = JSONArray((0..29).map { JSONObject().put("id", "turn-$it").put("status", "failed")
                .put("error", JSONObject().put("message", "failure"))
                .put("items", JSONArray().put(JSONObject().put("id", "item-$it").put("type", "agentMessage").put("text", "😀".repeat(25000)))) })
            val source = JSONObject().put("thread", JSONObject().put("id", "t").put("turns", turns))
                .put("nativeRevision", "stable").put("requests", JSONArray().put(JSONObject().put("key", "approval")))
            store.saveSnapshot("t", source); store.saveSnapshot("t", source)
            assertEquals(1, writes.count { it.startsWith("snapshot-") })
            val cached = store.loadSnapshot("t")!!
            assertTrue(cached.toString().toByteArray().size <= 2 * 1024 * 1024)
            assertTrue(cached.getJSONObject("thread").getJSONArray("turns").length() < 30)
            assertTrue(cached.objects("requests").isEmpty()); assertTrue(cached.isNull("nativeRevision"))
            assertEquals(30, source.getJSONObject("thread").getJSONArray("turns").length())
            assertEquals("approval", source.getJSONArray("requests").getJSONObject(0).string("key"))
            source.put("status", "running") // The same history revision can have newer live state.
            store.saveSnapshot("t", source)
            assertEquals(2, writes.count { it.startsWith("snapshot-") })
            assertEquals("running", store.loadSnapshot("t")!!.string("status"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun imageFreezeCapturesClickTimeDraftAndCheckpointSurvivesRestartWithNewerEdits() = runBlocking {
        val directory = Files.createTempDirectory("rivo-freeze-batch").toFile()
        try {
            val store = ConversationStore(directory)
            val bytes = ByteArray(1024 * 1024) { 7 }
            store.enqueueDraft("t", ConversationDraft("submitted", listOf(StoredDraftImage("image", bytes))))
            val captured = store.captureDraftAsync("t")
            store.enqueueDraft("t", ConversationDraft("newer edit"))
            val frozen = captured.await()
            assertEquals("submitted", frozen.text)
            bytes[0] = 9
            assertEquals(7, frozen.images.single().bytes[0].toInt())
            store.checkpointSentDraft("request", "t", frozen)
            val reopened = ConversationStore(directory)
            assertTrue(reopened.sentDraftCheckpoints().getValue("request").string("fingerprint").startsWith("v2:"))
            assertEquals("newer edit", reopened.loadDraft("t").text)
            withContext(Dispatchers.IO) { reopened.markSentDraftResolved("request", JSONObject().put("status", "accepted")) }
            reopened.clearAcceptedSentDraft("request")
            assertEquals("newer edit", reopened.loadDraft("t").text)
        } finally { directory.deleteRecursively() }
    }
}
