package dev.rivo.relay.ui.sites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitesScreen(api: RelayApi, nodeName: String) {
    var sites by remember(api) { mutableStateOf(emptyList<NativeResource>()) }
    var cursor by remember(api) { mutableStateOf<String?>(null) }
    var loading by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var loaded by remember(api) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load(more: Boolean) {
        if (loading) return
        loading = true; error = null
        try {
            val previousCursor = if (more) cursor else null
            val response = api.call("/v1/sites" + (previousCursor?.let { "?cursor=" + URLEncoder.encode(it, "UTF-8") } ?: ""))
            val page = response.objects("items").map { item ->
                val url = resourceWebUrl(item.optString("url", ""))
                val access = when(item.optString("accessMode")) { "public" -> "公开"; "private" -> "私有"; "workspace" -> "工作区"; "restricted" -> "受限访问"; else -> "" }
                NativeResource(item.string("id"), item.string("title").ifBlank { "未命名站点" }, url, "appgarden",
                    if (url == null) "尚未发布" else access.ifBlank { "打开网站" })
            }
            sites = (if (more) sites + page else page).distinctBy { it.key }
            cursor = (response.opt("cursor") as? String)?.takeIf { it.isNotBlank() && it != previousCursor }
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = if (e is ApiException && e.status == 404) "当前节点尚不支持 Sites，请更新节点" else "无法读取 Sites，请检查节点连接及 ChatGPT 登录后重试" }
        finally { loading = false }
    }
    LaunchedEffect(api) { load(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Sites", style = MaterialTheme.typography.headlineMedium); Text(nodeName, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { scope.launch { load(false) } }, enabled = !loading) { Text("刷新") }
        }
        PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { load(false) } }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                error?.let { notice -> item { Text(notice, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) } }
                if (loaded && sites.isEmpty() && error == null) item { Text("当前账号暂无站点", Modifier.padding(20.dp)) }
                items(sites, key = { it.key }) { ResourceRow(it); HorizontalDivider() }
                if (cursor != null) item { TextButton(onClick = { scope.launch { load(true) } }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
            }
        }
    }
}
