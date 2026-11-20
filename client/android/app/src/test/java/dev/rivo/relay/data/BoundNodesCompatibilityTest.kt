package dev.rivo.relay.data

import dev.rivo.relay.connection.PairingRepository
import org.junit.Assert.*
import org.junit.Test

class BoundNodesCompatibilityTest {
    @Test fun revokedBindingsRemainInUpdateAssessmentWhileAddressBookmarksDoNot() {
        val values = mutableMapOf("nodes" to """[{"base":"http://node","name":"old","nodeId":"n","deviceId":"d","token":""},{"base":"http://bookmark","name":"bookmark"}]""")
        val repository = PairingRepository(values::get, { key, value -> values[key] = value }, { values.remove(it) })
        assertEquals(1, repository.boundNodes().size)
        assertFalse(repository.boundNodes().single().authorized)
        assertNull(repository.connection("http://node"))
    }
}
