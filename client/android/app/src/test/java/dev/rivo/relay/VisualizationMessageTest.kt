package dev.rivo.relay

import dev.rivo.relay.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

class VisualizationMessageTest {
    private val path = "/Users/ethan/Documents/Rivo-Relay/workspace/designs/new-session-layout.html"
    private val marker = "visualize{\"path\":\"$path\"}"

    @Test fun visualizationReadsHtmlFromAuthenticatedNode() = kotlinx.coroutines.runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        val remotePath = "/remote/workspace/布局 draft #1.html"
        val html = "<html><body>远程可视化</body></html>"
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(org.json.JSONObject()
            .put("encoding", "utf-8").put("mimeType", "text/html").put("text", html).toString()))
        server.start()
        try {
            val api = dev.rivo.relay.data.RelayApi(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "test-token")
            val source = "visualize" + org.json.JSONObject().put("path", remotePath) + ""
            val attachment = reviewMessageParts(source).single() as ReviewMessagePart.Visualization
            val result = readFileSource(attachment.path, api::readFile)
            assertEquals(html, result.getString("text"))
            assertEquals("text/html", result.getString("mimeType"))
            val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("/v1/files", request.requestUrl!!.encodedPath)
            assertEquals(remotePath, request.requestUrl!!.queryParameter("path"))
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun sessionAttachmentPreservesSurroundingText() {
        val parts = reviewMessageParts("建议用「底部集中」。\n\n$marker\n后文")
        assertEquals(3, parts.size)
        assertEquals(ReviewMessagePart.Visualization(path), parts[1])
        assertEquals(path, nativeFilePath(path, ""))
        assertTrue((parts[0] as ReviewMessagePart.Text).value.contains("底部集中"))
        assertEquals("后文", (parts[2] as ReviewMessagePart.Text).value)
    }

    @Test fun malformedAndStreamingAttachmentsRemainText() {
        for (source in listOf(marker.dropLast(1), "visualize{}", "visualize{\"path\":17}",
            "visualize{\"path\":\"https://example.com/a.html\"}", "visualize{\"path\":\"a.html\"}garbage")) {
            assertEquals(listOf(ReviewMessagePart.Text(source)), reviewMessageParts(source))
        }
    }

    @Test fun codeExamplesNeverBecomeAttachments() {
        for (source in listOf("````text\n```\n$marker\n````", "~~~\n$marker\n~~~", "```text\n```not-a-close\n$marker\n```", "`$marker`")) {
            assertEquals(listOf(ReviewMessagePart.Text(source)), reviewMessageParts(source))
        }
    }

    @Test fun multipleAttachmentsAndEscapedPaths() {
        assertEquals(2, reviewMessageParts("$marker\n$marker").filterIsInstance<ReviewMessagePart.Visualization>().size)
        assertEquals("designs/布局 draft.html", parseVisualizationPath(" visualize{\"path\":\"designs\\/布局 draft.html\"} "))
    }
}
