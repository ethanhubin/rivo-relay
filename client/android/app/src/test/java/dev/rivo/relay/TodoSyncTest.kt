package dev.rivo.relay

import dev.rivo.relay.data.*
import dev.rivo.relay.ui.todos.TodosController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TodoSyncTest {
    private fun api(server: MockWebServer) = RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())

    @Test fun selectedDeletesCanChainAfterEachDurableLocalCommit() = runBlocking {
        val folder = Files.createTempDirectory("rivo-todo-batch-delete").toFile()
        val server = MockWebServer()
        val remaining = mutableMapOf("one" to JSONObject().put("id", "one").put("title", "One"),
            "two" to JSONObject().put("id", "two").put("title", "Two"))
        val deleted = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(remaining) {
                if (request.method == "DELETE") {
                    val id = request.path!!.substringAfterLast('/')
                    remaining.remove(id); deleted += id
                    MockResponse().setBody("""{"deleted":true}""")
                } else MockResponse().setBody(JSONObject().put("todos", JSONArray(remaining.values.toList())).toString())
            }
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = ConversationStore(folder)
            store.saveTodoState(JSONObject().put("version", 1).put("items", JSONArray(remaining.values.toList())).put("operations", JSONArray()))
            val controller = TodosController(api(server), scope, { throw it }, store)
            withTimeout(5_000) { controller.state.first { it.items.size == 2 } }
            val items = controller.state.value.items.associateBy { it.id }
            val finished = CompletableDeferred<Unit>()
            controller.delete(items.getValue("one")) {
                assertFalse(controller.state.value.mutating)
                controller.delete(items.getValue("two")) { finished.complete(Unit) }
            }
            withTimeout(5_000) { finished.await(); controller.refresh().join() }
            assertTrue(controller.state.value.items.isEmpty())
            assertEquals(0, controller.state.value.pendingCount)
            assertEquals(listOf("one", "two"), synchronized(remaining) { deleted.toList() })
        } finally { scope.cancel(); server.shutdown(); folder.deleteRecursively() }
    }

    @Test fun lostCreateResponsePersistsOutboxAndRestartReplaysTheSameTodoId() = runBlocking {
        val folder = Files.createTempDirectory("rivo-todo-test").toFile()
        val server = MockWebServer()
        val writes = AtomicInteger()
        val accepted = AtomicReference<JSONObject?>()
        val ids = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    val body = JSONObject(request.body.readUtf8())
                    synchronized(ids) { ids += body.getString("id") }
                    accepted.set(body)
                    if (writes.incrementAndGet() == 1) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    return MockResponse().setBody(JSONObject().put("todo", body).toString())
                }
                return MockResponse().setBody(JSONObject().put("todos", JSONArray(listOf(accepted.get()))).toString())
            }
        }
        server.start()
        val firstScope = CoroutineScope(coroutineContext + SupervisorJob())
        val secondScope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = AtomicReference<Throwable?>()
        try {
            val first = TodosController(api(server), firstScope, failure::set, ConversationStore(folder))
            first.saveDetails(null, "Offline task", "Notes", JSONObject().put("dueDate", "2026-11-01")) {}
            withTimeout(5_000) { first.state.first { it.pendingCount == 1 && it.syncError != null && !it.syncing } }
            val stored = ConversationStore(folder).loadTodoState()!!
            assertEquals(1, stored.objects("operations").size)
            assertEquals("Offline task", stored.objects("items").single().string("title"))
            firstScope.cancel()
            val restored = TodosController(api(server), secondScope, failure::set, ConversationStore(folder))
            withTimeout(5_000) { restored.refresh().join() }
            assertEquals(0, restored.state.value.pendingCount)
            assertTrue(restored.state.value.online)
            assertEquals("Offline task", restored.state.value.items.single().title)
            assertEquals(2, ids.size)
            assertEquals(ids[0], ids[1])
            assertNull(failure.get())
            assertTrue(ConversationStore(folder).loadTodoState()!!.objects("operations").isEmpty())
        } finally { firstScope.cancel(); secondScope.cancel(); server.shutdown(); folder.deleteRecursively() }
    }

    @Test fun dispatchWaitsForSuccessfulSynchronizationAndNeverRunsOffline() = runBlocking {
        val server = MockWebServer()
        val item = JSONObject().put("id", "todo").put("title", "Task").put("completed", false)
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"offline"}}"""))
        server.enqueue(MockResponse().setBody(JSONObject().put("todos", JSONArray(listOf(item))).toString()))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val called = AtomicInteger()
        try {
            val controller = TodosController(api(server), scope, { throw it })
            controller.prepareDispatch("todo") { called.incrementAndGet() }
            withTimeout(5_000) { controller.state.first { it.syncError != null && !it.syncing } }
            assertEquals(0, called.get())
            controller.prepareDispatch("todo") { called.incrementAndGet() }
            withTimeout(5_000) { controller.state.first { it.online && !it.syncing } }
            yield()
            assertEquals(1, called.get())
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun replayedDeleteTreatsMissingItemAsAcknowledged() = runBlocking {
        val folder = Files.createTempDirectory("rivo-todo-delete").toFile()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"missing"}}"""))
        server.enqueue(MockResponse().setBody("""{"todos":[]}"""))
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = ConversationStore(folder)
            store.saveTodoState(JSONObject().put("version", 1).put("items", JSONArray()).put("operations", JSONArray(listOf(
                JSONObject().put("id", "delete-command").put("todoId", "deleted").put("method", "DELETE").put("body", JSONObject())
            ))))
            val controller = TodosController(api(server), scope, { throw it }, store)
            withTimeout(5_000) { controller.refresh().join() }
            assertEquals(0, controller.state.value.pendingCount)
            assertTrue(controller.state.value.online)
            assertEquals("DELETE", server.takeRequest().method)
        } finally { scope.cancel(); server.shutdown(); folder.deleteRecursively() }
    }
}
