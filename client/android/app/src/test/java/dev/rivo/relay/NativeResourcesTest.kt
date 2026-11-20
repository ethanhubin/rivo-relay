package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeResourcesTest {
    private fun tool() = JSONObject("""{"type":"mcpToolCall","status":"completed","server":"codex_apps","tool":"documents.read","result":{"content":[{"type":"resource_link","uri":"https://a.example","title":"Report"}]}}""")
    @Test fun resourceAudienceAndUnsafeSchemes() {
        val item = tool()
        item.getJSONObject("result").put("content", org.json.JSONArray("""[
          {"type":"resource_link","uri":"https://a.example","title":"Report","name":"a"},
          {"type":"resource_link","uri":"https://private.example","annotations":{"audience":["assistant"]}},
          {"type":"resource_link","uri":"javascript:alert(1)"},
          {"type":"resource","resource":{"uri":"https://embedded.example"}}
        ]"""))
        assertEquals(listOf("Report"), nativeResources(item).map { it.title })
        assertNull(resourceWebUrl("https://user:pass@example.com"))
        item.put("status","inProgress")
        assertTrue(nativeResources(item).isEmpty())
    }

    @Test fun sameTurnDeduplicatesAcrossGroupsButOtherTurnsRemain() {
        fun message(id: String, turn: String, title: String) = NativeMessage(id, turn, "mcpToolCall", "",
            tool().apply { getJSONObject("result").getJSONArray("content").getJSONObject(0).put("title", title) })
        val a = NativeTimelineRow.Activities(listOf(message("a", "t", "Old")))
        val b = NativeTimelineRow.Activities(listOf(message("b", "t", "New")))
        val c = NativeTimelineRow.Activities(listOf(message("c", "other", "Other")))
        val rows = nativeResourceRows(listOf(a, b, c))
        assertFalse(rows.containsKey(a.key))
        assertEquals("New", rows.getValue(b.key).single().title)
        assertEquals("Other", rows.getValue(c.key).single().title)
    }
    @Test fun dynamicResourceAndKnownIconMetadata() {
        val item = JSONObject("""{"type":"dynamicToolCall","status":"completed","success":true,"contentItems":[{"type":"resource_link","uri":"https://a.example","name":"File"}]}""")
        assertEquals("File", nativeResources(item).single().title)
        val metadata = JSONObject("""{"server":"codex_apps","tool":"opaque","invocationResourceUri":"/connector_abc/implicit_link::connector_abc/get_document","toolIcons":["/images/ecosystem/apps/documents/icon.png"]}""")
        assertEquals("documents", nativeConnector(metadata))
    }

}
