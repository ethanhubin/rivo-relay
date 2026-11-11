package dev.rivo.relay

import dev.rivo.relay.codex.canDeleteThread
import dev.rivo.relay.data.ThreadSummary
import org.junit.Assert.*
import org.junit.Test

class DeleteSessionTest {
    private val idle = ThreadSummary("id", "name", "", "idle", "/tmp", "project")
    @Test fun onlyKnownIdleThreadsWithoutPendingWorkCanBeDeleted() {
        assertTrue(canDeleteThread(idle))
        for (thread in listOf(idle.copy(status = "running"), idle.copy(status = "waiting"),
            idle.copy(status = "unknown"), idle.copy(nativeExecution = "running"),
            idle.copy(nativeExecution = "unknown"), idle.copy(queueDepth = 1),
            idle.copy(hasOutstandingWork = true), idle.copy(recoveryRequired = true))) {
            assertFalse(thread.toString(), canDeleteThread(thread))
        }
    }
}
