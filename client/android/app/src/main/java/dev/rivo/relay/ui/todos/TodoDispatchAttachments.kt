package dev.rivo.relay.ui.todos

import android.content.Context
import android.net.Uri
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.data.Todo
import dev.rivo.relay.data.string
import dev.rivo.relay.ui.chat.prepareFile
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun stageTodoAttachments(context: Context, api: RelayApi, todo: Todo, body: JSONObject): JSONObject {
    val local = todo.localAttachments()
    if (local.isEmpty()) return body
    check(api.call("/v1/node").optJSONObject("capabilities")?.optBoolean("todoAttachmentUpload") == true) {
        "当前节点不支持待办附件上传，请更新节点后重试"
    }
    val uploaded = JSONArray()
    for (attachment in local) {
        val result = try {
            val prepared = prepareFile(context, Uri.parse(attachment.string("path")))
            try { api.uploadTodoFile(prepared.name, prepared.file) } finally { prepared.file.delete() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("无法上传「${attachment.string("name")}」，请确认本机文件仍可打开后重试", e)
        }
        uploaded.put(JSONObject().put("name", result.getString("name")).put("path", result.getString("path")))
    }
    return body.put("uploadedAttachments", uploaded)
}
