package dev.rivo.relay

import dev.rivo.relay.codex.recordSubmissionFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SubmissionFailureTest {
    @Test fun outcomePersistenceFailureRetainsSubmissionIdAndReportsTheStorageError() = runBlocking {
        val original = IOException("network failed")
        val disk = IOException("disk full")
        var cleared = false
        val reported = recordSubmissionFailure(original, true, { throw disk }, { cleared = true })
        assertSame(disk, reported)
        assertFalse(cleared)
        assertTrue(reported.suppressed.contains(original))
    }

    @Test fun uncertainNetworkOutcomeKeepsReceiptEvenAfterSuccessfulRecording() = runBlocking {
        val original = IOException("response lost")
        var recorded = false
        var cleared = false
        assertSame(original, recordSubmissionFailure(original, false, { recorded = true }, { cleared = true }))
        assertTrue(recorded)
        assertFalse(cleared)
    }

    @Test fun definitiveOutcomeClearsOnlyAfterRecordingAndReportsClearFailure() = runBlocking {
        val steps = mutableListOf<String>()
        val original = IOException("rejected")
        val disk = IOException("cannot retire receipt")
        val reported = recordSubmissionFailure(original, true, { steps += "record" }, { steps += "clear"; throw disk })
        assertEquals(listOf("record", "clear"), steps)
        assertSame(disk, reported)
    }
}
