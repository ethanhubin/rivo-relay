package dev.rivo.relay.connection

import java.net.URI
import java.net.URLDecoder

data class PairingInput(val baseUrl: String, val code: String) {
    override fun toString(): String = "PairingInput(baseUrl=$baseUrl, code=<redacted>)"
}

object PairingParser {
    fun parse(addressOrLink: String, explicitCode: String = "", allowLocalHttp: Boolean = false): PairingInput {
        val uri = try { URI(addressOrLink.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的节点地址或配对链接") }
        require(uri.host != null && uri.userInfo == null && uri.query == null) { "节点地址格式无效" }
        val local = uri.host.lowercase() in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")
        require(uri.scheme == "https" || (allowLocalHttp && local && uri.scheme == "http")) { "请使用 HTTPS 节点地址" }
        val fragmentParts = uri.rawFragment?.split('&').orEmpty()
        val version = fragmentParts.firstOrNull { it.startsWith("v=") }?.substringAfter('=')
        if (fragmentParts.any { it.startsWith("code=") }) require(version == "1") { "不支持此配对链接版本，请重新生成二维码" }
        val fragmentCode = uri.rawFragment?.split('&')?.firstOrNull { it.startsWith("code=") }?.substringAfter('=')?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { throw IllegalArgumentException("配对链接无效") }
        }
        val code = fragmentCode ?: explicitCode.trim()
        require(code.isNotBlank() && code.length <= 512) { "请输入节点显示的配对码" }
        val path = uri.path.orEmpty().removeSuffix("/").removeSuffix("/pair")
        val base = URI(uri.scheme, null, uri.host, uri.port, path, null, null).toASCIIString().trimEnd('/')
        return PairingInput(base, code)
    }
}
