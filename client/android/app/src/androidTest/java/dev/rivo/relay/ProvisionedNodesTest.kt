package dev.rivo.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.connection.PairingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only validation on an already authenticated deployment; never clears app data. */
@RunWith(AndroidJUnit4::class)
class ProvisionedNodesTest {
    @Test fun existingLoginCanReadBothProvisionedNodes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = PairingRepository(CredentialStore(context))
        val active = requireNotNull(repository.restore())
        val targets = listOf("https://ethanmacbook-pro.tail791ea5.ts.net:18766", "https://e2mac-mini.tail791ea5.ts.net:18766")
        try {
            for (base in targets) {
                assertTrue(repository.saved().any { it.base == base && it.authorized })
                val node = requireNotNull(repository.select(base))
                assertEquals(active.deviceId, node.deviceId)
                val status = node.api().call("/v1/node")
                assertEquals(node.nodeId, status.getString("id"))
                assertTrue(status.getBoolean("connected"))
                assertTrue(node.api().call("/v1/projects").has("projects"))
            }
        } finally { repository.select(active.base) }
        assertEquals(active.serialized(), repository.restore()?.serialized())
    }
}
