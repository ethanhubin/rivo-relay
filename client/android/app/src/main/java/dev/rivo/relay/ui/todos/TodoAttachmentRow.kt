package dev.rivo.relay.ui.todos

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.rivo.relay.data.string
import dev.rivo.relay.ui.chat.ComposerAttachmentRow
import dev.rivo.relay.ui.chat.draftThumbnail
import dev.rivo.relay.ui.chat.ComposerImageAttachmentRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.rivo.relay.ui.chat.prepareImage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal fun isTodoImage(context: Context, attachment: JSONObject): Boolean =
    attachment.string("type") == LOCAL_TODO_ATTACHMENT && runCatching {
        context.contentResolver.getType(Uri.parse(attachment.string("path"))) in setOf("image/jpeg", "image/png", "image/webp")
    }.getOrDefault(false)

internal fun todoAttachmentOpenIntent(context: Context, attachment: JSONObject): android.content.Intent {
    val uri = Uri.parse(attachment.string("path"))
    val declared = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
    return android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, dev.rivo.relay.ui.chat.fileMimeType(attachment.string("name"), declared))
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Composable
internal fun TodoAttachmentRow(attachment: JSONObject, enabled: Boolean, openEnabled: Boolean,
    onOpen: () -> Unit, onRemove: () -> Unit, index: Int) {
    val context = LocalContext.current
    val path = attachment.string("path")
    val mime = remember(path) { runCatching { context.contentResolver.getType(Uri.parse(path)) }.getOrNull().orEmpty() }
    val localCanOpen = if (attachment.string("type") == LOCAL_TODO_ATTACHMENT) remember(path, mime) {
        todoAttachmentOpenIntent(context, attachment).resolveActivity(context.packageManager) != null
    } else dev.rivo.relay.ui.chat.fileOpenKind(attachment.string("name"), mime) != dev.rivo.relay.ui.chat.FileOpenKind.EXTERNAL
    val image = remember(path) { isTodoImage(context, attachment) }
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (image) try { val bytes = prepareImage(context, Uri.parse(path)).bytes; bitmap = withContext(Dispatchers.Default) { draftThumbnail(bytes) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The attachment can still be opened to display the read error. */ }
    }
    if (image) ComposerImageAttachmentRow(bitmap, enabled, enabled && openEnabled, onOpen, onRemove,
        Modifier.testTag("todo_attachment_$index"), Modifier.testTag("todo_attachment_remove_$index"))
    else ComposerAttachmentRow(attachment.string("name"), enabled, enabled && openEnabled, onOpen, onRemove,
        Modifier.testTag("todo_attachment_$index"), removeModifier = Modifier.testTag("todo_attachment_remove_$index"), mimeType = mime, canOpen = localCanOpen)
}
