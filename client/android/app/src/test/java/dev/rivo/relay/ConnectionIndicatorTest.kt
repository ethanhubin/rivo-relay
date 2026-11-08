package dev.rivo.relay

import dev.rivo.relay.connection.ConnectionStatus
import dev.rivo.relay.connection.indicatorState
import dev.rivo.relay.data.RecoveryState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionIndicatorTest {
    @Test fun successfulTransportStaysConnectedRegardlessOfPageRefresh() {
        val status = ConnectionStatus(httpCode = 200, checkedAt = 1)
        assertEquals(RecoveryState.CONNECTED, status.indicatorState(null))
        assertEquals(RecoveryState.CONNECTED, status.indicatorState(RecoveryState.CONNECTED))
        assertEquals(RecoveryState.CONNECTING, status.indicatorState(RecoveryState.CONNECTING))
        assertEquals(RecoveryState.DISCONNECTED, status.indicatorState(RecoveryState.DISCONNECTED))
    }
    @Test fun unknownFailedAndUnauthorizedConnectionsAreNotGreen() {
        assertEquals(RecoveryState.CONNECTING, ConnectionStatus().indicatorState(RecoveryState.CONNECTED))
        assertEquals(RecoveryState.DISCONNECTED, ConnectionStatus(failed = true, checkedAt = 1).indicatorState(null))
        assertEquals(RecoveryState.DISCONNECTED, ConnectionStatus(httpCode = 401, checkedAt = 1).indicatorState(null))
    }
}
