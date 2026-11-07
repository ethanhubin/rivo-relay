package dev.rivo.relay.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(version: String, draft: Boolean = false, apk: Boolean = true) = JSONObject()
        .put("tag_name", "v$version").put("draft", draft).put("prerelease", true).put("body", "Changes")
        .put("assets", JSONArray().apply { if (apk) put(JSONObject().put("name", "rivo-relay-$version-debug.apk")
            .put("state", "uploaded").put("size", 100)
            .put("browser_download_url", "$RELEASES_PAGE/download/v$version/rivo-relay-$version-debug.apk")) })

    @Test fun numericOrderIncludesPreviewAndIgnoresDraftOrMissingApk() {
        val raw = JSONArray().put(release("0.2.9")).put(release("0.2.10"))
            .put(release("0.3.0", draft = true)).put(release("0.4.0", apk = false)).toString()
        assertEquals("0.2.10", latestApkRelease(raw)?.version)
        assertTrue(latestApkRelease(raw)!!.preview)
        assertTrue(compareVersions("0.2.10", "0.2.9") > 0)
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
        assertNull(versionParts("vgarbage"))
        assertNull(versionParts("0.2.999999999999999"))
    }
    @Test fun rejectsForeignAssetUrlsAndAmbiguousBuilds() {
        val item = release("0.2.11")
        item.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://untrusted.test/app.apk")
        assertNull(latestApkRelease(JSONArray().put(item).toString()))
        val duplicate = release("0.2.11")
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        assertNull(latestApkRelease(JSONArray().put(duplicate).toString()))
    }
    @Test fun checkUsesPublicEndpointAndNeverOffersDowngrade() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val updates = AppUpdates(endpoint = server.url("/releases").toString())
            repeat(3) { server.enqueue(MockResponse().setBody(JSONArray().put(release("0.2.10")).toString())) }
            assertEquals("0.2.10", updates.check("0.2.9")?.version)
            assertNull(updates.check("0.2.10"))
            assertNull(updates.check("0.2.11"))
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        } finally { server.shutdown() }
    }
    @Test fun failureIsNotReportedAsUpToDateAndCanRetry() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val updates = AppUpdates(endpoint = server.url("/releases").toString())
            for (response in listOf(MockResponse().setResponseCode(403), MockResponse().setBody("not json"), MockResponse().setBody("[]"))) {
                server.enqueue(response)
                try { updates.check("0.2.9"); fail("must report failure") } catch (_: java.io.IOException) { }
            }
            server.enqueue(MockResponse().setBody(JSONArray().put(release("0.2.10")).toString()))
            assertEquals("0.2.10", updates.check("0.2.9")?.version)
        } finally { server.shutdown() }
    }
}
