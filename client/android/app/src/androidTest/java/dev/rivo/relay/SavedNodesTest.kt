package dev.rivo.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.connection.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedNodesTest {
    @Test fun savedAddressesAndCredentialsSurviveSwitchingAndInvalidation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = context.createDeviceProtectedStorageContext()
        val vault = CredentialStore(isolated)
        val oldConnection = vault.read("connection")
        val oldNodes = vault.read("nodes")
        try {
            vault.remove("connection"); vault.remove("nodes")
            val a = NodeConnection("https://a.example", "A", "node-a", "device-a", "test-secret-a")
            vault.save("connection", a.serialized())
            val repository = PairingRepository(vault)
            assertTrue(repository.saved().single().authorized)
            repository.forget()
            assertNull(repository.restore())
            assertEquals("node-a", repository.select(a.base)?.nodeId)
            repository.invalidate()
            assertFalse(repository.saved().single().authorized)
            assertNull(repository.select(a.base))
            try { repository.pair("https://b.example", ""); fail("Missing code must fail before network") }
            catch (_: IllegalArgumentException) { }
            assertEquals(setOf(a.base, "https://b.example"), PairingRepository(vault).saved().map { it.base }.toSet())
            assertFalse(vault.read("nodes")!!.contains("test-secret-a"))
        } finally {
            if (oldConnection == null) vault.remove("connection") else vault.save("connection", oldConnection)
            if (oldNodes == null) vault.remove("nodes") else vault.save("nodes", oldNodes)
        }
    }
}
