package dev.rivo.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.connection.*
import dev.rivo.relay.data.NodeAppUpdates
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in live read-only check. Pairing fixture is provisioned privately by the operator. */
@RunWith(AndroidJUnit4::class)
class GatewayConnectionTest {
    @Test fun livePairingRoutesMigrationAndUpdates(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "gateway-verification.json")
        assumeTrue("No live gateway fixture supplied", fixture.isFile)
        val input = JSONObject(fixture.readText())
        val vault = CredentialStore(context)
        val names = listOf("connection", "nodes", "update-routes")
        val before = names.associateWith(vault::read)
        val devices = linkedSetOf<String>()
        val results = JSONObject()
        try {
            names.forEach(vault::remove)
            val repository = PairingRepository(vault)
            val first = repository.pair(input.getString("url"), "")
            devices.add(first.deviceId)
            assertEquals(input.getString("nodeId"), first.nodeId)
            val relay = repository.setMode(ConnectionMode.RELAY)!!
            assertEquals(first.nodeId, relay.api().call("/v1/node").getString("id"))
            assertTrue(relay.api().call("/v1/todos").has("todos"))
            results.put("relay", true)
            val direct = repository.setMode(ConnectionMode.DIRECT)!!
            assertEquals(first.nodeId, direct.api().call("/v1/node").getString("id"))
            results.put("direct", true)
            val upgraded = repository.pair(input.getString("upgradeUrl"), "")
            devices.add(upgraded.deviceId)
            assertEquals(first.deviceId, upgraded.deviceId)
            assertEquals(first.storageBase, upgraded.storageBase)
            assertEquals(1, repository.saved().size)
            results.put("identityPreserved", true)
            val record = JSONObject(upgraded.serialized())
            val fallback = upgraded.routes!!.copy(direct = "http://10.0.2.2:1", mode = ConnectionMode.AUTO)
            record.put("routes", fallback.json())
            vault.save("connection", record.toString())
            RouteMemory.write("route:" + upgraded.nodeId, fallback.direct)
            assertEquals(first.nodeId, repository.restore()!!.api().call("/v1/node").getString("id"))
            results.put("automaticFallback", true)
            vault.save("update-routes", upgraded.routes.copy(mode = ConnectionMode.RELAY).json().toString())
            val updater = NodeAppUpdates.configured(context)
            val release = updater.check("0.0.0")!!
            val apk = updater.download(release, File(context.cacheDir, "gateway-update-check"))
            assertEquals(release.size, apk.length())
            apk.delete()
            results.put("updateSha256", release.sha256)
        } finally {
            results.put("deviceIds", org.json.JSONArray(devices.toList()))
            File(context.filesDir, "gateway-verification-result.json").writeText(results.toString())
            for ((key, value) in before) if (value == null) vault.remove(key) else vault.save(key, value)
            fixture.delete()
        }
    }
}
