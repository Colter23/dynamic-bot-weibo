package top.colter.dynamic.weibo

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoResource
import top.colter.dynamic.core.link.ParsedLink

class WeiboResolvedVideoTest {
    @Test
    fun `数字和短码链接解析后的下载都复用首次详情请求`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var downloads = 0
        server.createContext("/video.mp4") {
            downloads++
            it.responseHeaders.add("Content-Type", "video/mp4")
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { output -> output.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            for (url in listOf("https://m.weibo.cn/detail/1234567890123456", "https://weibo.com/123/abc")) {
                var details = 0
                val client = WeiboClient(WeiboPublisherConfig(cookie = "SUB=test-cookie"), videoHttpClient = HttpClient.newHttpClient(), textTransport = { request ->
                    details++
                    response(request, """{"ok":1,"idstr":"1234567890123456","mblogid":"abc","user":{"idstr":"123","screen_name":"测试"},"text":"正文","page_info":{"object_type":"video","page_title":"测试视频","media_info":{"stream_url":"$base/video.mp4","duration":60}}}""")
                })
                val gateway = object : WeiboGateway {
                    override suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot? = null
                    override suspend fun fetchPostDetail(postId: String): WeiboPostSnapshot? = client.fetchPostDetail(postId)
                }
                val platform = PlatformId.of("weibo")
                val resolver = WeiboLinkResolver(platform, { gateway }, WeiboDynamicMapper(), { null })
                val parsed = assertNotNull(resolver.parseLink(url))
                val resolution = assertIs<LinkResolution.Dynamic>(resolver.resolveLink(parsed))
                assertEquals("abc", resolution.update.key.externalId)
                assertEquals(parsed.targetId, assertNotNull(resolution.video).id)
                val result = client.downloadVideoLink(LinkVideoDownloadRequest(parsed,
                    Files.createTempDirectory("resolved-weibo-id").toFile(), 1024, resource = resolution.video))
                assertEquals(4L, result.fileSizeBytes)
                assertEquals(1, details)
            }
            assertEquals(2, downloads)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `resolved video avoids detail request and only stale resources refresh once`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mp4") {
            it.responseHeaders.add("Content-Type", "video/mp4")
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { output -> output.write(bytes) }
        }
        for (status in listOf(403, 404, 500)) server.createContext("/$status") {
            it.sendResponseHeaders(status, -1)
            it.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val platform = PlatformId.of("weibo")
            for (path in listOf("video.mp4", "403", "404", "500", "missing")) {
                var details = 0
                val client = WeiboClient(WeiboPublisherConfig(cookie = "SUB=test-cookie"), videoHttpClient = HttpClient.newHttpClient(), textTransport = { request ->
                    details++
                    response(request, """{"ok":1,"idstr":"12345","mblogid":"abc","user":{"idstr":"123","screen_name":"测试"},"text":"正文","page_info":{"object_type":"video","page_title":"测试视频","media_info":{"stream_url":"$base/video.mp4","duration":60}}}""")
                })
                val resource = if (path == "missing") null else LinkVideoResource(platform, "abc", 60, buildJsonObject {
                    put("title", "测试视频")
                    put("mediaUrl", "$base/$path")
                })
                val request = LinkVideoDownloadRequest(
                    ParsedLink(platform, LinkKinds.DYNAMIC, "abc", "https://weibo.com/detail/abc"),
                    Files.createTempDirectory("resolved-weibo-video").toFile(), 1024, resource = resource,
                )
                if (path == "500") {
                    assertFailsWith<WeiboVideoHttpException> { client.downloadVideoLink(request) }
                    assertEquals(0, details)
                } else {
                    assertEquals(4L, client.downloadVideoLink(request).fileSizeBytes)
                    assertEquals(if (path == "video.mp4") 0 else 1, details)
                }
            }
        } finally {
            server.stop(0)
        }
    }

    private fun response(request: HttpRequest, body: String): HttpResponse<String> = object : HttpResponse<String> {
        override fun statusCode(): Int = 200
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
