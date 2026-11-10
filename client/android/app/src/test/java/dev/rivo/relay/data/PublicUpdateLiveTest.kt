package dev.rivo.relay.data

import dev.rivo.relay.connection.NodeRoutes
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Opt-in: verifies the actual production downloader against the public VPS. */
class PublicUpdateLiveTest {
    @Test fun settingsAndChatDownloadVerifiedVpsApk() = runBlocking {
        assumeTrue(System.getenv("RIVO_LIVE_UPDATE_TEST") == "1")
        val updates = NodeAppUpdates.gateway(NodeRoutes(
            relay = "https://134.175.156.219:80/relay/" + "a".repeat(32), pin = "b".repeat(64)))
        val release = updates.check("0.0.0")!!
        val dir = Files.createTempDirectory("live-update").toFile()
        val results = JSONArray()
        try {
            for (kind in listOf("settings", "chat")) {
                val progress = mutableListOf<Long>()
                val start = System.nanoTime()
                val report: (Long, Long) -> Unit = { n, _ -> progress.add(n) }
                val file = if (kind == "settings") updates.download(release, dir, report)
                    else PublicUpdateLink.download(release.apkUrl, dir, OkHttpClient(), report)!!
                assertEquals(release.size, file.length()) // Both production paths already verify SHA-256.
                assertEquals(release.size, progress.last())
                assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
                results.put(JSONObject().put("entry", kind).put("seconds", (System.nanoTime() - start) / 1e9)
                    .put("size", file.length()).put("sha256", release.sha256).put("version", release.version))
            }
            File("../../workspace/artifacts/download-observability/live-results.json").apply {
                parentFile.mkdirs(); writeText(results.toString(2))
            }
            println(results.toString())
        } finally { dir.deleteRecursively() }
        Unit
    }
}
