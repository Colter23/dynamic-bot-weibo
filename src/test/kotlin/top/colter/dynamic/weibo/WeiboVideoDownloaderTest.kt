package top.colter.dynamic.weibo

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoQuality
import top.colter.dynamic.core.link.ParsedLink
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeiboVideoDownloaderTest {
    @Test
    fun `select video source within requested quality`() {
        val video = videoCard(
            WeiboVideoSourceSnapshot(480, "https://example.com/480.mp4"),
            WeiboVideoSourceSnapshot(1080, "https://example.com/1080.mp4"),
        )

        assertEquals("https://example.com/480.mp4", video.selectVideoSource(LinkVideoQuality.P720)?.url)
        assertEquals("https://example.com/1080.mp4", video.selectVideoSource(LinkVideoQuality.P1080)?.url)
        assertEquals("https://example.com/1080.mp4", video.selectVideoSource(LinkVideoQuality.AUTO_HIGHEST)?.url)

        val ultraVideo = videoCard(
            WeiboVideoSourceSnapshot(1080, "https://example.com/1080.mp4"),
            WeiboVideoSourceSnapshot(1500, "https://example.com/1500.mp4"),
            WeiboVideoSourceSnapshot(2200, "https://example.com/2200.mp4"),
        )
        assertEquals("https://example.com/1500.mp4", ultraVideo.selectVideoSource(LinkVideoQuality.P1080_PLUS)?.url)
        assertEquals("https://example.com/2200.mp4", ultraVideo.selectVideoSource(LinkVideoQuality.P4K)?.url)
    }

    @Test
    fun `download video stream to cache file`() = runBlocking {
        val bytes = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)
        var receivedReferer = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mp4") { exchange ->
            receivedReferer = exchange.requestHeaders.getFirst("Referer").orEmpty()
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val cacheDirectory = Files.createTempDirectory("weibo-video-download")
            val request = LinkVideoDownloadRequest(
                parsedLink = ParsedLink(
                    platformId = PlatformId.of(WEIBO_PLATFORM_ID),
                    kind = LinkKinds.DYNAMIC,
                    targetId = "R1CXrAEh5",
                    normalizedUrl = "https://weibo.com/detail/R1CXrAEh5",
                    sourceUrl = "https://weibo.com/detail/R1CXrAEh5",
                ),
                directory = cacheDirectory.toFile(),
                maxBytes = 1024,
                quality = LinkVideoQuality.P720,
            )
            val port = server.address.port
            val result = WeiboVideoDownloader(HttpClient.newHttpClient(), "test-agent")
                .download(
                    request = request,
                    video = videoCard(WeiboVideoSourceSnapshot(720, "http://127.0.0.1:$port/video.mp4")),
                    referer = "https://weibo.com/detail/R1CXrAEh5",
                )

            assertEquals(bytes.size.toLong(), result.fileSizeBytes)
            assertTrue(Files.readAllBytes(cacheDirectory.resolve("R1CXrAEh5-720p.mp4")).contentEquals(bytes))
            assertFalse(Files.exists(cacheDirectory.resolve("R1CXrAEh5-720p.part")))
            assertEquals("https://weibo.com/detail/R1CXrAEh5", receivedReferer)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `download cache is isolated by selected source quality`() = runBlocking {
        val lowBytes = byteArrayOf(4, 8, 15, 16)
        val highBytes = byteArrayOf(23, 42, 108, 1, 2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/480.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, lowBytes.size.toLong())
            exchange.responseBody.use { it.write(lowBytes) }
        }
        server.createContext("/1080.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, highBytes.size.toLong())
            exchange.responseBody.use { it.write(highBytes) }
        }
        server.start()
        try {
            val cacheDirectory = Files.createTempDirectory("weibo-video-quality-cache")
            val baseRequest = LinkVideoDownloadRequest(
                parsedLink = ParsedLink(
                    platformId = PlatformId.of(WEIBO_PLATFORM_ID),
                    kind = LinkKinds.DYNAMIC,
                    targetId = "R1CXrAEh5",
                    normalizedUrl = "https://weibo.com/detail/R1CXrAEh5",
                    sourceUrl = "https://weibo.com/detail/R1CXrAEh5",
                ),
                directory = cacheDirectory.toFile(),
                maxBytes = 1024,
                quality = LinkVideoQuality.P720,
            )
            val port = server.address.port
            val downloader = WeiboVideoDownloader(HttpClient.newHttpClient(), "test-agent")
            val video = videoCard(
                WeiboVideoSourceSnapshot(480, "http://127.0.0.1:$port/480.mp4"),
                WeiboVideoSourceSnapshot(1080, "http://127.0.0.1:$port/1080.mp4"),
            )

            downloader.download(baseRequest, video, "https://weibo.com/detail/R1CXrAEh5")
            downloader.download(
                baseRequest.copy(quality = LinkVideoQuality.AUTO_HIGHEST),
                video,
                "https://weibo.com/detail/R1CXrAEh5",
            )

            assertTrue(Files.readAllBytes(cacheDirectory.resolve("R1CXrAEh5-480p.mp4")).contentEquals(lowBytes))
            assertTrue(Files.readAllBytes(cacheDirectory.resolve("R1CXrAEh5-1080p.mp4")).contentEquals(highBytes))
        } finally {
            server.stop(0)
        }
    }

    private fun videoCard(vararg sources: WeiboVideoSourceSnapshot): WeiboMediaCardSnapshot {
        return WeiboMediaCardSnapshot(
            kind = WeiboMediaCardKind.VIDEO,
            title = "测试视频",
            mediaUrl = sources.maxByOrNull { it.quality }?.url,
            videoSources = sources.toList(),
            durationSeconds = 60,
        )
    }
}
