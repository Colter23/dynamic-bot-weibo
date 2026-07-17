package top.colter.dynamic.weibo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoDownloadResult
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

internal class WeiboVideoDownloader(
    private val httpClient: HttpClient,
    private val userAgent: String,
) {
    suspend fun download(
        request: LinkVideoDownloadRequest,
        video: WeiboMediaCardSnapshot,
        referer: String,
    ): LinkVideoDownloadResult {
        require(request.maxBytes >= 0) { "视频大小上限不能为负数" }
        val source = video.selectVideoSource(request.quality)
            ?: error("微博视频未提供可下载的 MP4 地址")
        val fileName = "${request.parsedLink.targetId.toVideoCacheFileName()}-${source.quality}p"
        val directory = request.directory
        val target = directory.resolve("$fileName.mp4")
        val temporary = directory.resolve("$fileName.part")

        if (target.isFile) {
            val cachedSize = target.length()
            if (request.maxBytes == 0L || cachedSize <= request.maxBytes) {
                return result(target, cachedSize, video)
            }
            target.delete()
        }

        directory.mkdirs()
        check(directory.isDirectory) { "无法创建视频缓存目录：${directory.absolutePath}" }
        Files.deleteIfExists(temporary.toPath())
        return try {
            val size = downloadToFile(source.url, referer, temporary, request.maxBytes)
            moveToTarget(temporary, target)
            result(target, size, video)
        } catch (error: CancellationException) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            throw error
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            throw error
        }
    }

    private suspend fun downloadToFile(
        url: String,
        referer: String,
        target: File,
        maxBytes: Long,
    ): Long = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
            .header("Accept", "video/*,application/octet-stream;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            if (response.statusCode() !in 200..299) {
                error("微博视频下载失败：status=${response.statusCode()}")
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (
                contentType.isNotBlank() &&
                !contentType.startsWith("video/", ignoreCase = true) &&
                !contentType.startsWith("application/octet-stream", ignoreCase = true)
            ) {
                error("微博视频下载响应不是视频：contentType=$contentType")
            }
            val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            if (maxBytes > 0 && contentLength > maxBytes) {
                error("微博视频文件大小 ${contentLength.formatMegabytes()} 超过限制 ${maxBytes.formatMegabytes()}")
            }
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (maxBytes > 0 && total > maxBytes) {
                        error("微博视频文件大小超过限制 ${maxBytes.formatMegabytes()}")
                    }
                    output.write(buffer, 0, count)
                }
                total
            }
        }
    }

    private fun moveToTarget(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun result(
        file: File,
        size: Long,
        video: WeiboMediaCardSnapshot,
    ): LinkVideoDownloadResult {
        return LinkVideoDownloadResult(
            video = MediaRef(file.absolutePath, MediaKind.VIDEO, mimeType = "video/mp4"),
            fileSizeBytes = size,
            title = video.title,
            durationSeconds = video.durationSeconds,
        )
    }

    private fun String.toVideoCacheFileName(): String {
        return replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            .trim('_', '.', ' ')
            .ifBlank { "weibo-video" }
    }

    private fun Long.formatMegabytes(): String {
        return "%.1fMB".format(java.util.Locale.ROOT, this / 1024.0 / 1024.0)
    }

    private companion object {
        private const val BUFFER_SIZE: Int = 32 * 1024
        private const val DOWNLOAD_TIMEOUT_SECONDS: Long = 60
    }
}
