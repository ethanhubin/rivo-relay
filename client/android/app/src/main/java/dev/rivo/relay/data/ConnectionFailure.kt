package dev.rivo.relay.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Classify failures without exposing request URLs, credentials or arbitrary exception messages. */
fun connectionFailure(error: Throwable, base: String = ""): String {
    val causes = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    val reason = when {
        causes.any { it is UnknownHostException } -> "无法解析节点域名（DNS）。请检查 VPN 的 DNS 设置。"
        causes.any { it is SSLException } -> "节点证书校验或安全连接失败（TLS）。"
        causes.any { it.message.orEmpty().let { m -> m.contains("EACCES") || m.contains("EPERM") || m.contains("Permission denied", true) } } -> "系统拒绝此 App 访问网络。请检查 Relay 的联网权限和 VPN 应用规则。"
        causes.any { it is SocketTimeoutException } -> "节点连接或响应超时。"
        causes.any { it is ConnectException } -> "无法建立节点连接。请检查节点地址、端口及 VPN 应用规则。"
        else -> "节点连接中断（${error.javaClass.simpleName.take(60)}）。"
    }
    val target = runCatching { java.net.URI(base).let { uri -> uri.host?.let { it + if (uri.port >= 0) ":${uri.port}" else "" } } }.getOrNull()
    return reason + (target?.let { "\n节点：$it" } ?: "") + "\n已提交操作不会自动重发。"
}
