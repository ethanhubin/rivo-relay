package dev.rivo.relay.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** QiGuang's partial-file cache, adapted to authenticated nodes and strong file validators. */
internal object ResumableDownload {
    private const val LIMIT = 1024L * 1024 * 1024
    private data class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val gates = mutableMapOf<String, Gate>()
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun validator(value: String?) = value?.takeIf { it.startsWith('"') && it.endsWith('"') && it.length <= 1024 }

    suspend fun get(directory: File, scope: String, source: String, client: OkHttpClient,
                    request: Request, progress: (Long, Long) -> Unit): File {
        require(request.method == "GET")
        // A download can safely try the next resolved address after a broken socket.
        // This client is never used for native mutations or submission replay.
        val reader = client.newBuilder().retryOnConnectionFailure(true).build()
        val key = digest("$scope\u0000$source")
        val identity = File(directory, key).absolutePath
        val gate = synchronized(gates) { gates.getOrPut(identity) { Gate() }.also { it.users++ } }
        try {
            return gate.mutex.withLock { withContext(Dispatchers.IO) {
                check(directory.mkdirs() || directory.isDirectory) { "无法创建下载缓存" }
                prune(directory)
                val part = File(directory, "$key.part")
                val metadata = File(directory, "$key.part.json")
                val completedMetadata = File(directory, "$key.json")
                val completed = runCatching { JSONObject(completedMetadata.readText()) }.getOrNull()
                var previousFile = completed?.optString("file")?.takeIf {
                    it.matches(Regex("${key}\\.[0-9a-f-]+\\.complete"))
                }?.let { File(directory, it) }?.takeIf { it.isFile && it.length() <= LIMIT }
                var saved = runCatching { JSONObject(metadata.readText()) }.getOrNull()
                if (!part.isFile || validator(saved?.optString("etag")) == null || part.length() > LIMIT) {
                    part.delete(); metadata.delete()
                    saved = completed?.takeIf { previousFile != null && validator(it.optString("etag")) != null }
                    if (saved == null) previousFile = null
                } else previousFile = null
                // Published files have immutable paths: a MediaPlayer/FileProvider can open an
                // earlier result while this key is being revalidated or retried.
                repeat(2) { attempt ->
                    val offset = if (part.isFile) part.length() else previousFile?.length() ?: 0L
                    val etag = validator(saved?.optString("etag"))
                    val builder = request.newBuilder().header("Accept-Encoding", "identity")
                    if (offset > 0 && etag != null) builder.header("Range", "bytes=$offset-").header("If-Range", etag)
                    val retry = transfer(reader, builder.build(), part, metadata, offset, etag, progress)
                    if (!retry) {
                        val published = if (part.isFile) {
                            val target = File(directory, "$key.${UUID.randomUUID()}.complete")
                            check(part.renameTo(target)) { "无法保存下载缓存" }
                            val record = JSONObject(metadata.readText()).put("file", target.name)
                            writeMetadata(completedMetadata, record)
                            metadata.delete()
                            target
                        } else requireNotNull(previousFile) { "下载缓存已失效" }
                        published.setLastModified(System.currentTimeMillis())
                        progress(published.length(), published.length())
                        return@withContext published
                    }
                    part.delete(); metadata.delete(); saved = null; previousFile = null
                    if (attempt == 1) throw IOException("服务器返回了无效的续传范围")
                }
                error("Unreachable download state")
            } }
        } finally {
            synchronized(gates) { if (--gate.users == 0) gates.remove(identity) }
        }
    }

    private suspend fun transfer(client: OkHttpClient, request: Request, part: File, metadata: File,
                                 offset: Long, previousTag: String?, progress: (Long, Long) -> Unit): Boolean {
        val finished = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                try { call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        finished.complete(Unit)
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use { reply ->
                            val tag = validator(reply.header("ETag"))
                            if (reply.code == 416) {
                                val total = Regex("bytes \\*/(\\d+)").matchEntire(reply.header("Content-Range").orEmpty())
                                    ?.groupValues?.get(1)?.toLongOrNull()
                                if (offset > 0 && total == offset && tag != null && tag == previousTag) return@use false
                                return@use true
                            }
                            if (!reply.isSuccessful) throw IOException("下载失败：HTTP ${reply.code}")
                            val body = reply.body ?: throw IOException("文件内容为空")
                            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(reply.header("Content-Range").orEmpty())
                            val start = range?.groupValues?.get(1)?.toLongOrNull()
                            val end = range?.groupValues?.get(2)?.toLongOrNull()
                            val rangeTotal = range?.groupValues?.get(3)?.toLongOrNull()
                            val partial = reply.code == 206
                            if (partial && (start != offset || (offset > 0 && part.length() != offset) || end == null || rangeTotal == null || end < offset ||
                                    end >= rangeTotal || (offset > 0 && (tag == null || tag != previousTag)) ||
                                    (body.contentLength() >= 0 && body.contentLength() != end - offset + 1))) return@use true
                            if (reply.header("Content-Encoding")?.let { !it.equals("identity", true) } == true)
                                throw IOException("文件传输编码不支持续传")
                            val initial = if (partial) offset else 0L
                            val total = if (partial) rangeTotal!! else body.contentLength()
                            require(total <= LIMIT) { "文件最大支持 1 GiB" }
                            if (!partial && part.exists()) check(part.delete()) { "无法重建下载缓存" }
                            writeMetadata(metadata, JSONObject().put("etag", tag ?: "").put("total", total))
                            var count = initial
                            try {
                                FileOutputStream(part, partial).use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(65536)
                                        while (true) {
                                            if (call.isCanceled()) throw IOException("下载已取消")
                                            val size = input.read(buffer); if (size < 0) break
                                            count += size
                                            require(count <= LIMIT && (total < 0 || count <= total)) { "文件超出声明大小" }
                                            output.write(buffer, 0, size); progress(count, total)
                                        }
                                    }
                                    output.fd.sync()
                                }
                                if (total >= 0 && count != total) throw IOException("下载不完整，请重试")
                            } catch (error: Exception) {
                                if (tag == null) { part.delete(); metadata.delete() }
                                throw error
                            }
                            false
                        } }
                        finished.complete(Unit)
                        if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                }) } catch (error: Exception) {
                    finished.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } finally {
            // Cancellation must finish closing the old writer before a retry owns this cache key.
            withContext(NonCancellable) { finished.await() }
        }
    }

    private fun writeMetadata(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { it.write(value.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(file)) { "无法保存下载状态" }
    }

    private fun prune(directory: File) {
        val active = synchronized(gates) { gates.keys.map { File(it).name }.toSet() }
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy(File::lastModified).orEmpty()
        var bytes = files.sumOf(File::length)
        val deadline = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        files.forEach { file ->
            if (file.name.substringBefore('.') !in active && (file.lastModified() < deadline || bytes > LIMIT * 2)) {
                val length = file.length(); if (file.delete()) bytes -= length
            }
        }
    }
}
