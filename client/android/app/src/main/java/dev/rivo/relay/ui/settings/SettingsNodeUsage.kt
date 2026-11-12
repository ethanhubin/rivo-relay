package dev.rivo.relay.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.rivo.relay.NodeRuntime
import dev.rivo.relay.codex.CatalogController
import dev.rivo.relay.codex.SubmissionState
import dev.rivo.relay.codex.SubmissionCoordinator
import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.connection.NodeConnection
import dev.rivo.relay.data.CodexUsage
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.data.USAGE_RESET_PATH
import dev.rivo.relay.ui.common.CodexQuotaProgressBar
import dev.rivo.relay.ui.common.UsageDetailsDialog
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** The caller keys this subtree by the inspected connection and API. */
@Composable
internal fun SettingsNodeUsage(connection: NodeConnection, api: RelayApi, refresh: Int,
    activeNode: NodeRuntime? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var operationError by remember { mutableStateOf<String?>(null) }
    val catalog = remember(api, activeNode) {
        activeNode?.catalog ?: CatalogController(api, scope, { operationError = it.message })
    }
    // Reuse the active node's coordinator. Other inspected nodes retain the same durable
    // submission key, so leaving this screen cannot cause an uncertain reset to be replayed.
    val submissions = remember(api, activeNode) {
        if (activeNode != null) activeNode.submissions else {
            val vault = CredentialStore(context)
            val storageKey = "pending:${connection.nodeId}:${connection.deviceId}:${connection.storageBase}"
            val pending = vault.read(storageKey)
            // A settings-only owner must not resolve another kind of pending operation.
            if (pending != null && JSONObject(pending).optString("path") != USAGE_RESET_PATH) null
            else SubmissionCoordinator(api, scope, vault, storageKey,
                onResolved = { _, response -> catalog.usageResetResolved(response) },
                failure = { operationError = it.message })
        }
    }
    val state by catalog.state.collectAsState()
    val submission = submissions?.state?.collectAsState()?.value ?: SubmissionState(busy = true)
    val connectionStatus by api.connectionStatus.collectAsState()
    var detailsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(catalog, refresh) { catalog.refreshUsage(true)?.join() }
    val usage = state.usage
    val canReset = !submission.busy && submission.pending == null &&
        !connectionStatus.failed && connectionStatus.httpCode in 200..299
    SettingsWeeklyUsage(usage, state.usageLoading, state.usageError) {
        detailsOpen = true
        operationError = null
        catalog.refreshUsage(true)
    }
    if (detailsOpen) UsageDetailsDialog(usage, state.usageLoading, operationError ?: state.usageError,
        onRefresh = { catalog.refreshUsage(true) }, onDismiss = { detailsOpen = false }, nodeName = connection.name,
        canReset = canReset, resetPending = submission.pending?.path == USAGE_RESET_PATH,
        resetNotice = if (submission.pending?.path == USAGE_RESET_PATH) submission.notice else state.usageResetNotice,
        onReset = { creditId ->
            if (canReset) {
                operationError = null
                catalog.clearUsageResetNotice()
                submissions?.submit(USAGE_RESET_PATH, JSONObject().put("creditId", creditId), "重置额度")
            }
        }, onReconcile = { submissions?.reconcile() })
}

@Composable
internal fun SettingsWeeklyUsage(usage: CodexUsage?, loading: Boolean, error: String?, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("settings_weekly_usage"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("周额度", style = MaterialTheme.typography.titleMedium)
        val weekly = usage?.weeklyWindow?.takeIf { it.windowMinutes == 7L * 24 * 60 }
        if (weekly != null) {
            Text("周额度剩余 ${weekly.remainingPercent}%", style = MaterialTheme.typography.bodyLarge)
            CodexQuotaProgressBar(weekly, Modifier.fillMaxWidth().height(4.dp))
            weekly.resetsAt?.let { reset ->
                val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                Text("${format.format(Date(reset * 1000))} 重置", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Text(if (loading) "正在读取额度…" else "周额度暂不可用",
            style = MaterialTheme.typography.bodyMedium)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).testTag("open_usage_details").padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(dev.rivo.relay.R.drawable.ic_openai), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Text("额度重置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(usage?.resetCredits?.availableCount?.let { "$it 次" }
                ?: if (loading) "读取中…" else "次数未知",
                modifier = Modifier.testTag("settings_reset_count"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("›", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
