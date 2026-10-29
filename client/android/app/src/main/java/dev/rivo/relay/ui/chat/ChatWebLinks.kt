package dev.rivo.relay.ui.chat

import android.content.Intent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import java.net.URI

internal fun isHttpWebLink(target: String): Boolean =
  runCatching {
    val uri = URI(target)
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
      !uri.rawAuthority.isNullOrBlank()
  }.getOrDefault(false)

private val plainWebLinkPattern = Regex("https?://[^\\s<>\"'`，。；！？、（）【】《》“”‘’]+", RegexOption.IGNORE_CASE)

/** Keep sentence punctuation outside links, but preserve balanced URL parentheses. */
internal fun plainWebLinkAt(source: String, index: Int): String? {
  if (!source.regionMatches(index, "http", 0, 4, ignoreCase = true)) return null
  if (index > 0 && (source[index - 1].isLetterOrDigit() && source[index - 1].code < 128 ||
      source[index - 1] == '_')) return null
  var target = plainWebLinkPattern.matchAt(source, index)?.value ?: return null
  while (target.isNotEmpty()) {
    val last = target.last()
    val opening = when (last) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> null }
    if (last in ".,;:!?" ||
      (opening != null && target.count { it == last } > target.count { it == opening })) {
      target = target.dropLast(1)
    } else break
  }
  return target.takeIf(::isHttpWebLink)
}

/** Returns an external-link action that opens the system handler only after confirmation. */
@Composable
internal fun rememberConfirmedWebLinkOpener(
  messageId: String,
  onSaveFile: ((String) -> Unit)? = null,
): (String) -> Unit {
  val uriHandler = LocalUriHandler.current
  val context = androidx.compose.ui.platform.LocalContext.current
  var pendingUrl by remember(messageId) { mutableStateOf<String?>(null) }
  var failed by remember(messageId) { mutableStateOf(false) }
  var shareFailed by remember(messageId) { mutableStateOf(false) }
  pendingUrl?.let { target ->
    AlertDialog(
      modifier = Modifier.testTag("message_web_link_dialog"),
      onDismissRequest = { pendingUrl = null },
      title = { Text("打开外部链接？") },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          Text("可使用浏览器打开，或将链接内容保存到本机：")
          SelectionContainer { Text(target) }
          TextButton(onClick = {
            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, target) }, null)) }
              .onSuccess { pendingUrl = null }
              .onFailure { shareFailed = true }
          }, modifier = Modifier.testTag("message_web_link_share")) { Text("分享") }
          if (shareFailed) Text("无法打开分享面板，请重试。", color = MaterialTheme.colorScheme.error)
          if (failed) Text("无法打开链接，请检查是否已安装浏览器。", color = MaterialTheme.colorScheme.error)
        }
      },
      confirmButton = {
        TextButton(onClick = {
          // Android intent matching expects a lowercase scheme.
          val normalized = target.substringBefore(':').lowercase() + target.substring(target.indexOf(':'))
          runCatching { uriHandler.openUri(normalized) }
            .onSuccess { pendingUrl = null }
            .onFailure { failed = true }
        }) { Text("用浏览器打开") }
      },
      dismissButton = {
        androidx.compose.foundation.layout.Row {
          if (onSaveFile != null) {
            TextButton(onClick = {
              val normalized = target.substringBefore(':').lowercase() + target.substring(target.indexOf(':'))
              onSaveFile(normalized)
              pendingUrl = null
            }) { Text("保存到本机") }
          }
          TextButton(onClick = { pendingUrl = null }) { Text("取消") }
        }
      },
    )
  }
  return { target ->
    if (isHttpWebLink(target)) {
      failed = false
      shareFailed = false
      pendingUrl = target
    }
  }
}

/** Use only the URL path for a filename; queries may contain access tokens. */
internal fun webLinkSaveName(target: String): String {
  val uri = URI(target)
  return uri.path.orEmpty().substringAfterLast('/')
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    .takeIf { it.isNotBlank() && it != "." && it != ".." }
    ?: "${uri.host ?: "webpage"}.html"
}
