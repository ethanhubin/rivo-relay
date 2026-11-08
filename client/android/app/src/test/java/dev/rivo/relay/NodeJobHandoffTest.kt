package dev.rivo.relay

import dev.rivo.relay.connection.NodeJobHandoff
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NodeJobHandoffTest {
    @Test fun rapidSwitchesWaitForOldCleanupBeforeWritingNewPendingIdentity() = runBlocking {
        val handoff = NodeJobHandoff()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        var savedId: String? = "old"
        val first = SupervisorJob()
        val firstScope = CoroutineScope(coroutineContext + first)
        firstScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) {
                cleaning.complete(Unit)
                releaseCleanup.await()
                savedId = null
            } }
        }
        val firstWait = handoff.retire(first)
        withTimeout(5000) { cleaning.await() }
        val middle = SupervisorJob()
        CoroutineScope(coroutineContext + middle).launch(start = CoroutineStart.UNDISPATCHED) {
            firstWait.joinAll()
            fail("The cancelled intermediate activation must never run")
        }
        val finalWait = handoff.retire(middle)
        val last = SupervisorJob()
        var activated = false
        val activation = CoroutineScope(coroutineContext + last).launch {
            finalWait.joinAll()
            savedId = "new"
            activated = true
        }
        try {
            middle.join()
            yield()
            assertFalse(activated)
            assertEquals("old", savedId)
            releaseCleanup.complete(Unit)
            withTimeout(5000) { activation.join() }
            assertTrue(activated)
            assertEquals("new", savedId)
            assertTrue(first.isCompleted)
        } finally { releaseCleanup.complete(Unit); first.cancel(); middle.cancel(); last.cancel() }
    }
}
