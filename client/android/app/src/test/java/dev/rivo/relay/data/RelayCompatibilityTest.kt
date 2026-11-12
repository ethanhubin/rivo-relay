package dev.rivo.relay.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayCompatibilityTest {
    @Test fun incompatibleNodeBlocksWriteBeforeTransportAndCanRecoverAfterUpgrade() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{id:n,compatibility:{apiVersions:[2]}}"))
            val api = RelayApi(server.url("/").toString().trimEnd('/'), "token", nodeId="n")
            try { api.call("/v1/threads/t/turns", "POST", JSONObject("{submissionId:s,text:hi}")); fail() }
            catch (e: ApiException) { assertEquals("api_incompatible", e.code) }
            assertEquals(1, server.requestCount)
            assertEquals("/v1/node", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("{id:n,compatibility:{apiVersions:[1],features:{'1':[]}}}"))
            api.call("/v1/node")
            server.enqueue(MockResponse().setBody("{status:accepted}"))
            assertEquals("accepted", api.call("/v1/threads/t/turns", "POST", JSONObject("{submissionId:s,text:hi}")).getString("status"))
            assertEquals(3, server.requestCount)
            assertFalse(api.supportsFeature("voice.direct"))
            try { api.call("/v1/todos", "POST"); fail() }
            catch (e: ApiException) { assertEquals("feature_unsupported", e.code) }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun legacyNodeKeepsBasicConnectionButDoesNotGainUpdateEligibility() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{id:n,releaseCode:999}"))
            server.enqueue(MockResponse().setBody("{projects:[]}"))
            val api = RelayApi(server.url("/").toString().trimEnd('/'), "token", nodeId="n")
            assertEquals(0, api.call("/v1/projects").getJSONArray("projects").length())
            assertNull(api.compatibility)
            assertTrue(api.compatibilityMessage.contains("未知"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun wrongIdentityNeverAllowsBusinessRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{id:wrong,compatibility:{apiVersions:[1]}}"))
            val api = RelayApi(server.url("/").toString().trimEnd('/'), "token", nodeId="n")
            try { api.call("/v1/threads/t/turns", "POST"); fail() }
            catch (e: ApiException) { assertEquals("node_identity_mismatch", e.code) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingOptionalFeaturesBlockOnlyTheirOperationsAndRefreshAfterReconnect() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{id:n,compatibility:{apiVersions:[1],features:{'1':[]}}}"))
            val api = RelayApi(server.url("/").toString().trimEnd('/'), "token", nodeId="n")
            for (body in listOf(JSONObject("{input:[{type:image,url:x}]}"), JSONObject("{text:hi,requireGoalSupport:true}"))) {
                try { api.call("/v1/threads/t/turns", "POST", body); fail() }
                catch (e: ApiException) { assertEquals("feature_unsupported", e.code) }
            }
            assertEquals(1, server.requestCount)
            api.resetTransport()
            server.enqueue(MockResponse().setBody("{id:n,compatibility:{apiVersions:[1],features:{'1':['session.goals']}}}"))
            assertTrue(api.supportsFeature("session.goals"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun malformedFeatureListCannotBypassKnownIncompatibleVersion() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{id:n,compatibility:{apiVersions:[2],features:false}}"))
            val api = RelayApi(server.url("/").toString().trimEnd('/'), "token", nodeId="n")
            try { api.call("/v1/threads/t/turns", "POST"); fail() }
            catch (e: ApiException) { assertEquals("api_incompatible", e.code) }
            assertEquals(1, server.requestCount)
        }
    }
}
