package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeResourcesTest {
    private fun tool(payload: String) = JSONObject("""{"type":"mcpToolCall","status":"completed","server":"codex_apps","tool":"sites.deploy_site_version","result":{"structuredContent":$payload}}""")
    @Test fun siteRequiresActualPublicationSuccess() {
        val item = tool("""{"project_id":"s","id":"deploy","status":"building","url":"https://site.example"}""")
        assertNull(nativeResources(item).single().url)
        item.getJSONObject("result").getJSONObject("structuredContent").put("status", "succeeded")
        assertEquals("https://site.example", nativeResources(item).single().url)
        item.getJSONObject("result").put("isError", true)
        assertTrue(nativeResources(item).isEmpty())
    }
    @Test fun wrappersAndPreviewAreDistinct() {
        val item = tool("""{"result":{"structured_content":{"project_id":"s","current_preview_url":"https://preview.example"}}}""")
        // One known transport wrapper inside structuredContent.
        assertEquals("打开预览", nativeResources(item).single().label)
    }
    @Test fun resourceAudienceAndUnsafeSchemes() {
        val item = tool("{}")
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
    @Test fun wrongConnectorDoesNotCreateSiteCard() {
        val item = tool("""{"project_id":"s","current_live_url":"https://site.example"}""").put("server","other")
        assertTrue(nativeResources(item).isEmpty())
    }

    @Test fun sameTurnDeduplicatesAcrossGroupsButOtherTurnsRemain() {
        fun message(id: String, turn: String, title: String) = NativeMessage(id, turn, "mcpToolCall", "",
            tool("""{"project_id":"s","title":"$title","current_live_url":"https://site.example"}"""))
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
        val metadata = JSONObject("""{"server":"codex_apps","tool":"opaque","invocationResourceUri":"/connector_abc/implicit_link::connector_abc/get_site","toolIcons":["/images/ecosystem/apps/appgarden/icon.png"]}""")
        assertEquals("appgarden", nativeConnector(metadata))
    }

    @Test fun siteListResultsAreClickableWithoutExpandingToolJson() {
        val item = tool("""{"items":[{"id":"a","title":"A","current_live_url":"https://a.example"},{"id":"b","title":"B","current_live_url":null}]}""").put("tool", "sites.list_sites")
        val resources = nativeResources(item)
        assertEquals(2, resources.size)
        assertEquals("https://a.example", resources[0].url)
        assertNull(resources[1].url)
    }
}
