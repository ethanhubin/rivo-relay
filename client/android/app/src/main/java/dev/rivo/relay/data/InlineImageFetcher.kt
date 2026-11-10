package dev.rivo.relay.data

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer
import java.util.Base64

internal fun decodeInlineImage(source: String): ByteArray {
    require(source.startsWith("data:image/") && source.length <= 28 * 1024 * 1024 &&
        source.substringBefore(',').endsWith(";base64") && ',' in source) { "图片编码无法预览" }
    return Base64.getDecoder().decode(source.substringAfter(',')).also {
        require(it.size <= 20 * 1024 * 1024) { "单张图片最大支持 20 MiB" }
    }
}

/** Decode pending attachments in Coil's fetch stage, off the UI thread. */
internal class InlineImageFetcher(private val source: String, private val options: Options) : Fetcher {
    override suspend fun fetch() = SourceResult(
        source = ImageSource(Buffer().write(decodeInlineImage(source)), options.context),
        mimeType = source.substringAfter("data:").substringBefore(';'),
        dataSource = DataSource.MEMORY,
    )

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == "data" && data.toString().startsWith("data:image/"))
                InlineImageFetcher(data.toString(), options) else null
    }
}
