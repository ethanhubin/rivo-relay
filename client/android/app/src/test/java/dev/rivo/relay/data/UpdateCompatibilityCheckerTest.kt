package dev.rivo.relay.data

import dev.rivo.relay.connection.NodeConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateCompatibilityCheckerTest {
    @Test fun checksOnlyTheCurrentConnection() = runTest {
        val active = NodeConnection("https://current.example", "当前节点", "current-id", "device", "token")
        var reads = 0
        val result = UpdateCompatibilityChecker.checkCurrent(active, setOf(1)) { connection ->
            assertEquals(active, connection)
            reads++
            JSONObject("{id:'current-id',compatibility:{apiVersions:[1]}}")
        }
        assertEquals(1, reads)
        assertTrue(result.allowed)
        assertEquals(CompatibilityState.COMPATIBLE, result.current?.state)
        assertTrue(UpdateCompatibilityChecker.checkCurrent(null, setOf(1)).allowed)
    }
    @Test fun offlineTimeoutAndRevokedAuthorizationAreUnknown() = runTest {
        for (error in listOf(java.io.IOException("offline"), ApiException(401, "unauthorized", "revoked"))) {
            val result = UpdateCompatibilityChecker.inspect("n", setOf(1)) { throw error }
            assertEquals(CompatibilityState.UNKNOWN, result.state)
            assertFalse(UpdateCompatibility(result).allowed)
        }
        val result = UpdateCompatibilityChecker.inspect("n", setOf(1)) { delay(13_000); JSONObject() }
        assertEquals(CompatibilityState.UNKNOWN, result.state)
        assertTrue(result.reason.contains("超时"))
    }
    @Test fun callerCancellationIsNeverConvertedToAnAssessment() = runTest {
        try {
            withTimeout(100) { UpdateCompatibilityChecker.inspect("n", setOf(1)) { delay(1000); JSONObject() } }
            fail()
        } catch (_: TimeoutCancellationException) { }
    }
    @Test fun freshReadReplacesPreviousCompatibleResult() = runTest {
        var info = JSONObject("{id:n,compatibility:{apiVersions:[1]}}")
        assertEquals(CompatibilityState.COMPATIBLE, UpdateCompatibilityChecker.inspect("n", setOf(1)) { info }.state)
        info = JSONObject("{id:n,compatibility:{apiVersions:[2]}}")
        assertEquals(CompatibilityState.INCOMPATIBLE, UpdateCompatibilityChecker.inspect("n", setOf(1)) { info }.state)
    }
}
