package dev.rivo.relay.ui.sites

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.rivo.relay.R
import dev.rivo.relay.data.NativeResource
import dev.rivo.relay.data.resourceWebUrl

val LocalSitesApi = staticCompositionLocalOf<dev.rivo.relay.data.RelayApi?> { null }

@Composable
fun ConnectorIcon(connector: String?, modifier: Modifier = Modifier) {
    val resource = when(connector) {
        "appgarden" -> R.drawable.connector_appgarden
        "chatgpt_space" -> R.drawable.connector_chatgpt_space
        "documents" -> R.drawable.connector_documents
        "pdf" -> R.drawable.connector_pdf
        "spreadsheets" -> R.drawable.connector_spreadsheets
        "presentations" -> R.drawable.connector_presentations
        "github" -> R.drawable.connector_github
        "slack" -> R.drawable.connector_slack
        "gmail" -> R.drawable.connector_gmail
        "notion" -> R.drawable.connector_notion
        else -> null
    }
    if (resource != null) Image(painterResource(resource), contentDescription = null, modifier = modifier.size(22.dp))
    else Text("↗", modifier = modifier)
}

@Composable
fun ResourceRow(resource: NativeResource) {
    var browsing by remember(resource.key) { mutableStateOf(false) }
    val url = resourceWebUrl(resource.url)
    Row(Modifier.fillMaxWidth().clickable(enabled = url != null, role = Role.Button) {
        browsing = true
    }.padding(vertical = 14.dp, horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectorIcon(resource.connector)
        Column(Modifier.weight(1f)) {
            Text(resource.title, style = MaterialTheme.typography.bodyLarge)
            if (url != null) Text(Uri.parse(url).host.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (resource.label !in setOf("打开网站", "打开链接")) Text(resource.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (url != null) Text("›")
    }
    if (browsing && url != null) SiteBrowser(url, resource.title, if (resource.connector == "appgarden") LocalSitesApi.current else null, resource.connector == "appgarden") { browsing = false }
}
