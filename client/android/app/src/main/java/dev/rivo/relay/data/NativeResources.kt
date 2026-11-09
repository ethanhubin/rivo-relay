package dev.rivo.relay.data

import org.json.JSONObject
import java.net.URI

data class NativeResource(val key: String, val title: String, val url: String?, val connector: String?, val label: String)

fun resourceWebUrl(value: String?): String? = value?.takeIf { it.length <= 8192 && it.none { c -> c.code < 32 } }?.let {
    runCatching { URI(it).takeIf { u -> u.scheme in setOf("https", "http") && !u.host.isNullOrBlank() && u.rawUserInfo == null }?.toASCIIString() }.getOrNull()
}

private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
private fun unwrapResult(value: JSONObject): JSONObject {
    val nested = value.optJSONObject("result")
    return value.optJSONObject("structuredContent") ?: value.optJSONObject("structured_content")
        ?: nested?.optJSONObject("structuredContent") ?: nested?.optJSONObject("structured_content") ?: nested ?: value
}

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
    val known = setOf("appgarden", "chatgpt_space", "documents", "pdf", "spreadsheets", "presentations", "github", "slack", "gmail", "notion")
    if (server == "codex_apps" && Regex("^/(connector_[^/]+)/implicit_link::\\1/[^/]+$").matches(uri)) {
        iconKeys.firstOrNull { it in known }?.let { return it }
    }
    if (server == "codex_apps") {
        val prefix = tool.substringBefore('.').substringBefore('_')
        if (tool.startsWith("sites.") || tool.startsWith("sites_")) return "appgarden"
        return when (prefix) { "github", "slack", "gmail", "notion", "pdf", "documents", "spreadsheets", "presentations" -> prefix
            else -> when { tool.startsWith("chatgpt_space") -> "chatgpt_space"; else -> null } }
    }
    if (uri.startsWith("connectors://sites/") || uri.startsWith("/sites/")) return "appgarden"
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
    if (connector == "appgarden") {
        val data = unwrapResult(unwrapResult(result))
        val invocation = item.optJSONObject("invocation") ?: item
        val tool = invocation.text("tool") ?: invocation.text("name")
        if (tool in setOf("sites.list_sites", "sites_list_sites")) {
            val sites = data.optJSONArray("items")
            if (sites != null) for (i in 0 until sites.length()) {
                val site = sites.optJSONObject(i) ?: continue
                val id = site.text("id") ?: continue
                val url = resourceWebUrl(site.text("current_live_url"))
                links += NativeResource("site:$id", site.text("title") ?: "Sites 站点", url, connector,
                    if (url != null) "打开网站" else "尚未发布")
            }
        }
        val args = item.optJSONObject("arguments") ?: item.optJSONObject("invocation")?.optJSONObject("arguments")
        val id = data.text("project_id") ?: args?.text("project_id") ?: data.text("id")?.takeIf { data.has("slug") || data.has("current_live_url") }
        if (id != null) {
            val status = data.text("status")
            val live = resourceWebUrl(data.text("current_live_url"))
            val preview = resourceWebUrl(data.text("current_preview_url"))
            val deployed = if (status == "succeeded") resourceWebUrl(data.text("deployment_url")) ?: resourceWebUrl(data.text("url")) else null
            val url = live ?: deployed ?: preview
            val label = when { status == "failed" -> "发布失败"; status in setOf("pending", "building", "publishing") -> "发布中"
                live != null || deployed != null -> "打开网站"; preview != null -> "打开预览"; else -> "尚未发布" }
            links += NativeResource("site:$id", data.text("title") ?: "Sites 站点", url, connector, label)
        }
    }
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

/** Latest result wins within a turn; prior title/URL can describe an existing published site. */
fun nativeResources(messages: List<NativeMessage>): List<NativeResource> {
    val results = linkedMapOf<String, NativeResource>()
    messages.forEach { message -> nativeResources(message.raw).forEach { next ->
        val key = "${message.turnId}:${next.key}"
        val old = results[key]
        results[key] = next.copy(title = if (next.title == "Sites 站点") old?.title ?: next.title else next.title,
            url = next.url ?: old?.url, label = if (next.url == null && old?.url != null && next.label == "尚未发布") old.label else next.label)
    } }
    return results.values.toList()
}

fun nativeResourceRows(rows: List<NativeTimelineRow>): Map<String, List<NativeResource>> {
    val owners = mutableMapOf<String, String>()
    val values = linkedMapOf<String, NativeResource>()
    rows.filterIsInstance<NativeTimelineRow.Activities>().forEach { row -> row.messages.forEach { message ->
        nativeResources(message.raw).forEach { resource ->
            val key = "${message.turnId}:${resource.key}"
            val old = values[key]
            values[key] = resource.copy(title = if (resource.title == "Sites 站点") old?.title ?: resource.title else resource.title,
                url = resource.url ?: old?.url, label = if (resource.url == null && old?.url != null && resource.label == "尚未发布") old.label else resource.label)
            owners[key] = row.key
        }
    } }
    return values.entries.groupBy({ owners.getValue(it.key) }, { it.value })
}
