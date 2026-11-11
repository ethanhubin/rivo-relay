package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.RelayEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RefreshPolicyTest {
    private fun event(method: String, params: String = "{}") = RelayEvent("native",
        JSONObject().put("method", method).put("params", JSONObject(params)))

    @Test fun unrelatedMetadataNeverWakesHiddenThreads() {
        for (method in listOf("relay/todos", "project/updated", "item/agentMessage/delta", "thread/tokenUsage/updated", "unknown"))
            assertEquals(method, emptySet<String>(), notificationRefreshTargets(event(method)))
        assertEquals(setOf("t"), notificationRefreshTargets(event("turn/completed", """{"threadId":"t"}""")))
        assertEquals(emptySet<String>(), notificationRefreshTargets(event("relay/writerDisconnected", """{"threadIds":[]}""")))
        assertNull(notificationRefreshTargets(event("relay/nativeDisconnected")))
        assertNull(notificationRefreshTargets(RelayEvent("connected", JSONObject())))
        assertEquals(setOf("t"), notificationRefreshTargets(RelayEvent("queue", JSONObject("""{"threadId":"t"}"""))))
    }

    @Test fun listEventsRespectProjectAndThreadIdentityButDiscoverNewMembers() {
        assertFalse(catalogEventRelevant(event("turn/completed", """{"threadId":"other"}"""), "p", setOf("t")))
        assertTrue(catalogEventRelevant(event("turn/completed", """{"threadId":"t"}"""), "p", setOf("t")))
        val created = event("thread/started", """{"thread":{"id":"new","projectId":"other"}}""")
        assertFalse(catalogEventRelevant(created, "p", setOf("t")))
        assertTrue(catalogEventRelevant(created, "__rivo_recent__", setOf("t")))
        assertTrue(catalogEventRelevant(event("thread/started", """{"threadId":"new"}"""), "p", setOf("t")))
        assertFalse(catalogEventRelevant(event("relay/writerDisconnected", """{"threadIds":[]}"""), "p", setOf("t")))
    }

    @Test fun submissionRefreshUsesFrozenTargetAndDoesNotRefreshUnrelatedCollections() {
        val turn = submissionRefresh(PendingSubmission("s", "/v1/threads/a%2Fb/turn", "send"), JSONObject())
        assertEquals(setOf("a/b"), turn.threads)
        assertFalse(turn.projects || turn.catalog || turn.todos)
        val project = submissionRefresh(PendingSubmission("s", "/v1/projects", "add"), JSONObject())
        assertTrue(project.projects)
        assertTrue(project.threads.isEmpty())
        assertFalse(project.catalog || project.todos)
        val pin = submissionRefresh(PendingSubmission("s", "/v1/threads/t/pin", "pin", "t"), JSONObject())
        assertTrue(pin.threads.isEmpty()); assertTrue(pin.catalog)
        val fork = submissionRefresh(PendingSubmission("s", "/v1/threads/source/fork", "fork", "source"),
            JSONObject("""{"receipt":{"threadId":"new"}}"""))
        assertEquals(setOf("new"), fork.threads)
        val todo = submissionRefresh(PendingSubmission("s", "/v1/todos/t/dispatch", "dispatch"),
            JSONObject("""{"receipt":{"threadId":"new"}}"""))
        assertEquals(setOf("new"), todo.threads)
        assertTrue(todo.todos && todo.catalog)
    }
}
