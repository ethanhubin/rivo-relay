package dev.rivo.relay.data

import org.json.JSONObject
import java.net.URI

data class NativeResource(val key: String, val title: String, val url: String?, val connector: String?, val label: String)

fun resourceWebUrl(value: String?): String? = value?.takeIf { it.length <= 8192 && it.none { c -> c.code < 32 } }?.let {
    runCatching { URI(it).takeIf { u -> u.scheme in setOf("https", "http") && !u.host.isNullOrBlank() && u.rawUserInfo == null }?.toASCIIString() }.getOrNull()
}

private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

/** Local asset keys only; never load an arbitrary tool-provided icon URL with node credentials. */
fun nativeConnector(item: JSONObject): String? {
    val invocation = item.optJSONObject("invocation") ?: item
    val server = invocation.text("server") ?: invocation.text("namespace")
    val tool = invocation.text("tool") ?: invocation.text("name") ?: ""
    val uri = item.text("invocationResourceUri") ?: item.text("mcpAppResourceUri") ?: ""
    val icons = item.optJSONArray("toolIcons") ?: item.optJSONArray("tool_icons")
        ?: item.optJSONObject("_meta")?.optJSONArray("tool_icons")
    val iconKeys = icons?.let { a -> (0 until a.length()).mapNotNull { index ->
        Regex("^/images/ecosystem/apps/([a-z_]+)/icon(?:_dark)?\\.png$").matchEntire(a.optString(index))?.groupValues?.get(1)
    } }.orEmpty()
    val known = setOf("chatgpt_space", "documents", "pdf", "spreadsheets", "presentations", "github", "slack", "gmail", "notion")
    if (server == "codex_apps" && Regex("^/(connector_[^/]+)/implicit_link::\\1/[^/]+$").matches(uri)) {
        iconKeys.firstOrNull { it in known }?.let { return it }
    }
    if (server == "codex_apps") {
        val prefix = tool.substringBefore('.').substringBefore('_')
        return when (prefix) { "github", "slack", "gmail", "notion", "pdf", "documents", "spreadsheets", "presentations" -> prefix
            else -> when { tool.startsWith("chatgpt_space") -> "chatgpt_space"; else -> null } }
    }
    return null
}

fun nativeResources(item: JSONObject): List<NativeResource> {
    if (item.string("type").replace("_", "").lowercase() !in setOf("mcptoolcall", "dynamictoolcall")) return emptyList()
    if (item.string("status") !in setOf("completed", "succeeded") || item.opt("success") == false ||
        item.opt("error")?.let { it != JSONObject.NULL } == true) return emptyList()
    val result = item.optJSONObject("result") ?: if (item.opt("success") == true && item.has("contentItems")) JSONObject().put("content", item.opt("contentItems")) else return emptyList()
    if (result.optBoolean("isError") || result.text("type") == "error") return emptyList()
    val connector = nativeConnector(item)
    val links = mutableListOf<NativeResource>()
    val content = result.optJSONArray("content") ?: item.optJSONArray("contentItems")
    if (content != null) for (i in 0 until content.length()) {
        val block = content.optJSONObject(i) ?: continue
        val audience = block.optJSONObject("annotations")?.optJSONArray("audience")
        val targets = audience?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        if ("assistant" in targets && "user" !in targets) continue
        if (block.text("type") != "resource_link") continue
        val url = resourceWebUrl(block.text("uri")) ?: continue
        links += NativeResource("url:$url", block.text("title") ?: block.text("name") ?: URI(url).host, url, connector, "打开链接")
    }
    return links.distinctBy { it.url ?: it.key }
}

/** Latest resource result wins within a turn. */
fun nativeResources(messages: List<NativeMessage>): List<NativeResource> {
    val results = linkedMapOf<String, NativeResource>()
    messages.forEach { message -> nativeResources(message.raw).forEach { next ->
        val key = "${message.turnId}:${next.key}"
        results[key] = next
    } }
    return results.values.toList()
}

fun nativeResourceRows(rows: List<NativeTimelineRow>): Map<String, List<NativeResource>> {
    val owners = mutableMapOf<String, String>()
    val values = linkedMapOf<String, NativeResource>()
    rows.filterIsInstance<NativeTimelineRow.Activities>().forEach { row -> row.messages.forEach { message ->
        nativeResources(message.raw).forEach { resource ->
            val key = "${message.turnId}:${resource.key}"
            values[key] = resource
            owners[key] = row.key
        }
    } }
    return values.entries.groupBy({ owners.getValue(it.key) }, { it.value })
}
