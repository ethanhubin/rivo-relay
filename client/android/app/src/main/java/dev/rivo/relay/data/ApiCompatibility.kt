package dev.rivo.relay.data

import dev.rivo.relay.BuildConfig
import org.json.JSONObject

/** Release numbers deliberately do not participate in this contract. */
data class ApiCompatibility(val apiVersions: Set<Int>, val features: Map<Int, Set<String>>) {
    fun selected(client: Set<Int> = CLIENT_VERSIONS): Int? = apiVersions.intersect(client).maxOrNull()
    fun supports(feature: String): Boolean = selected()?.let { feature in features[it].orEmpty() } == true
    companion object {
        val CLIENT_VERSIONS: Set<Int> = BuildConfig.API_VERSIONS.split(',').map(String::toInt).toSet()
        fun versions(value: JSONObject?): Set<Int>? {
            val array = value?.optJSONArray("apiVersions") ?: return null
            if (array.length() == 0) return null
            val result = mutableSetOf<Int>()
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                if (item !is Int || item <= 0 || !result.add(item)) return null
            }
            return result
        }
        fun parse(value: JSONObject?): ApiCompatibility? {
            val versions = versions(value) ?: return null
            if (value!!.has("features") && value.optJSONObject("features") == null) return null
            val raw = value.optJSONObject("features") ?: JSONObject()
            val features = mutableMapOf<Int, Set<String>>()
            for (key in raw.keys()) {
                val version = key.toIntOrNull()?.takeIf { it in versions && it.toString() == key } ?: return null
                val array = raw.optJSONArray(key) ?: return null
                val items = mutableSetOf<String>()
                for (i in 0 until array.length()) {
                    val item = array.opt(i)
                    if (item !is String || item.isBlank() || !items.add(item)) return null
                }
                features[version] = items
            }
            return ApiCompatibility(versions, features)
        }
    }
}

enum class CompatibilityState { COMPATIBLE, INCOMPATIBLE, UNKNOWN }
data class NodeCompatibility(val state: CompatibilityState, val reason: String)
data class UpdateCompatibility(val current: NodeCompatibility? = null, val candidateKnown: Boolean = true) {
    val allowed: Boolean get() = candidateKnown && (current == null || current.state == CompatibilityState.COMPATIBLE)
    val summary: String get() = if (!candidateKnown) "新版未提供有效兼容声明，暂缓安装，请重新检查更新"
        else if (current == null) "尚未绑定节点"
        else if (allowed) "当前节点兼容"
        else if (current.state == CompatibilityState.INCOMPATIBLE) "当前节点尚不支持新版 App，请升级当前节点后重试"
        else "无法确认当前节点兼容性，暂缓安装，请检查连接后重试"
}

fun assessNode(id: String, candidate: Set<Int>?, info: JSONObject): NodeCompatibility {
    if (info.optString("id") != id || id.isBlank())
        return NodeCompatibility(CompatibilityState.UNKNOWN, "节点身份不匹配，暂缓安装")
    val contract = ApiCompatibility.parse(info.optJSONObject("compatibility"))
    return when {
        candidate == null || contract == null -> NodeCompatibility(CompatibilityState.UNKNOWN, "兼容性未知，请先升级节点后重新检查")
        contract.selected(candidate) == null -> NodeCompatibility(CompatibilityState.INCOMPATIBLE, "等待节点升级（没有共同 API 版本）")
        else -> NodeCompatibility(CompatibilityState.COMPATIBLE, "基础兼容（API v${contract.selected(candidate)}）")
    }
}
