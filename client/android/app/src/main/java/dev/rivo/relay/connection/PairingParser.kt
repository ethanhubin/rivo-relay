package dev.rivo.relay.connection

import java.net.URI
import java.net.URLDecoder

data class PairingInput(val baseUrl: String, val code: String, val routes: NodeRoutes? = null, val nodeId: String = "", val updateSource: Boolean = false) {
    override fun toString(): String = "PairingInput(baseUrl=$baseUrl, code=<redacted>)"
}

object PairingParser {
    fun parse(addressOrLink: String, explicitCode: String = "", allowLocalHttp: Boolean = false): PairingInput {
        val uri = try { URI(addressOrLink.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的节点地址或配对链接") }
        require(uri.host != null && uri.userInfo == null && uri.query == null) { "节点地址格式无效" }
        val local = uri.host.lowercase() in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")
        require(uri.scheme == "https" || (uri.scheme == "http" && ((allowLocalHttp && local) || uri.host == NodeEndpoints.TAILNET_IP))) { "请使用 HTTPS 或已配置的 Tailscale IP 节点地址" }
        val fragmentParts = uri.rawFragment?.split('&').orEmpty()
        val version = fragmentParts.firstOrNull { it.startsWith("v=") }?.substringAfter('=')
        if (fragmentParts.any { it.startsWith("code=") }) require(version == "1" || version == "2") { "不支持此配对链接版本，请重新生成二维码" }
        val fragmentCode = uri.rawFragment?.split('&')?.firstOrNull { it.startsWith("code=") }?.substringAfter('=')?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { throw IllegalArgumentException("配对链接无效") }
        }
        val code = fragmentCode ?: explicitCode.trim()
        require(code.isNotBlank() && code.length <= 512) { "请输入节点显示的配对码" }
        val path = uri.path.orEmpty().removeSuffix("/").removeSuffix("/pair")
        val base = URI(uri.scheme, null, uri.host, uri.port, path, null, null).toASCIIString().trimEnd('/')
        if (version == "2") {
            val fields = fragmentParts.associate { entry ->
                val key = entry.substringBefore('=')
                key to URLDecoder.decode(entry.substringAfter('=', ""), "UTF-8")
            }
            require(fields.size == fragmentParts.size) { "配对链接含重复字段" }
            val routes = NodeRoutes(fields["direct"].orEmpty(), fields["relay"].orEmpty(), fields["pin"].orEmpty()).validated()
            val nodeId = fields["node"].orEmpty()
            require(nodeId.matches(Regex("[a-f0-9-]{36}"))) { "节点身份无效" }
            require(base == routes.direct || base == routes.relay) { "配对入口与节点地址不匹配" }
            return PairingInput(base, code, routes, nodeId, fields["updates"] == "1")
        }
        return PairingInput(base, code)
    }
}
