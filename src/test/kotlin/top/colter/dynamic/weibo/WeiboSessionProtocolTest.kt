package top.colter.dynamic.weibo

import java.net.URI
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpClient
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeiboSessionProtocolTest {
    @Test
    fun `SSO follows domain scoped redirects then commits validated cookies`() = runBlocking {
        val requests = mutableListOf<HttpRequest>()
        val client = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long; SUB=old"), textTransport = { request ->
            requests += request
            when (request.uri().host) {
                "login.sina.com.cn" -> {
                    assertTrue(request.headers().firstValue("Cookie").orElse("").contains("ALC=long"))
                    response(request, 302, headers = mapOf("Location" to listOf("https://passport.weibo.com/sso/crossdomain?ticket=secret")))
                }
                "passport.weibo.com" -> {
                    assertFalse(request.headers().firstValue("Cookie").orElse("").contains("ALC="))
                    response(request, 302, headers = mapOf(
                        "Set-Cookie" to listOf("SUB=new; Domain=.weibo.com; Path=/; Secure; HttpOnly; Max-Age=31536000"),
                        "Location" to listOf("https://passport.weibo.cn/sso/crossdomain?ticket=mobile"),
                    ))
                }
                "passport.weibo.cn" -> response(request, 302, headers = mapOf(
                    "Set-Cookie" to listOf("SUB=mobile; Domain=.weibo.cn; Path=/"),
                    "Location" to listOf("https://weibo.com/"),
                ))
                else -> {
                    assertTrue(request.headers().firstValue("Cookie").orElse("").contains("SUB=new"))
                    response(request, 200, accountPage())
                }
            }
        })

        assertEquals("123", client.refreshLoginSession("123").account?.userId)
        assertEquals(4, requests.size)
        assertTrue(client.exportCookiesJson().contains("ALC"))
        val restored = WeiboCookieJar(client.exportCookiesJson())
        assertTrue(restored.headerFor(URI("https://weibo.com/")).contains("SUB=new"))
        assertTrue(restored.headerFor(URI("https://passport.weibo.cn/")).contains("SUB=mobile"))
        assertTrue(restored.canRefresh())
    }

    @Test
    fun `HTML replace and META refresh are supported`() = runBlocking {
        for (html in listOf(
            """<script>location.replace('https://passport.weibo.com/sso/crossdomain?ticket=test&amp;entry=miniblog')</script>""",
            """<meta http-equiv="refresh" content="0; url='https://passport.weibo.com/sso/crossdomain?ticket=test'"/>""",
        )) {
            val client = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long"), textTransport = { request ->
                when (request.uri().host) {
                    "login.sina.com.cn" -> response(request, 200, html)
                    "passport.weibo.com" -> response(request, 302, headers = mapOf(
                        "Location" to listOf("https://weibo.com/"),
                        "Set-Cookie" to listOf("SUB=new; Domain=.weibo.com; Path=/"),
                    ))
                    else -> response(request, 200, accountPage())
                }
            })
            assertEquals("123", client.refreshLoginSession().account?.userId)
        }
    }

    @Test
    fun `visitor cookies and account changes never replace current session`() = runBlocking {
        for (page in listOf("<html>visitor</html>", accountPage("456"))) {
            val client = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long; SUB=old"), textTransport = { request ->
                if (request.uri().host == "login.sina.com.cn") response(request, 302, headers = mapOf(
                    "Location" to listOf("https://weibo.com/"),
                    "Set-Cookie" to listOf("ALC=rotated; Domain=.login.sina.com.cn; Path=/"),
                )) else response(request, 200, page, mapOf("Set-Cookie" to listOf("SUB=visitor; Domain=.weibo.com; Path=/")))
            })
            assertFailsWith<WeiboSessionException> { client.refreshLoginSession("123") }
            assertTrue(client.exportCookieHeader().contains("SUB=old"))
            assertFalse(client.exportCookiesJson().contains("rotated"))
        }
    }

    @Test
    fun `failure pages redirects and cycles are classified without leaking tickets`() = runBlocking {
        val cases = listOf(
            "<script>location.replace('https://weibo.com/?retcode=6102')</script>" to WeiboSessionFailure.LOGIN_REQUIRED,
            "<script>window.wbBotDetector</script>" to WeiboSessionFailure.CHALLENGE,
            "<meta http-equiv='refresh' content=\"0;url='https://evil.example/?ticket=secret'\">" to WeiboSessionFailure.PROTOCOL,
            "<script>location.replace('https://weibo.com/login.php')</script>" to WeiboSessionFailure.LOGIN_REQUIRED,
        )
        for ((body, reason) in cases) {
            val client = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long"), textTransport = { response(it, 200, body) })
            val failure = assertFailsWith<WeiboSessionException> { client.refreshLoginSession() }
            assertEquals(reason, failure.failure)
            assertFalse(failure.message.orEmpty().contains("secret"))
        }
        val looping = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long"), textTransport = { request ->
            response(request, 302, headers = mapOf("Location" to listOf(request.uri().toString())))
        })
        assertEquals(WeiboSessionFailure.PROTOCOL, assertFailsWith<WeiboSessionException> { looping.refreshLoginSession() }.failure)
    }

    @Test
    fun `checking login does not mistake SUB or status 200 for an account`() = runBlocking {
        val client = WeiboClient(WeiboPublisherConfig(cookie = "SUB=old"), textTransport = {
            response(it, 200, "<html>guest</html>", mapOf("Set-Cookie" to listOf("SUB=guest; Domain=.weibo.com; Path=/")))
        })
        assertEquals(top.colter.dynamic.core.plugin.PublisherLoginStatus.FAILED, client.checkLoginState().status)
        assertTrue(client.exportCookieHeader().contains("SUB=old"))
    }

    @Test
    fun `login validation preserves concurrent API cookie updates and deletions`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = WeiboClient(WeiboPublisherConfig(cookie = "SUB=old; XSRF-TOKEN=old"), textTransport = { request ->
            if (request.uri().path == "/ajax/statuses/show") {
                response(request, 200, "{}", mapOf("Set-Cookie" to listOf(
                    "SUB=rotated; Domain=.weibo.com; Path=/",
                    "XSRF-TOKEN=; Domain=.weibo.com; Path=/; Max-Age=0",
                )))
            } else {
                entered.complete(Unit)
                release.await()
                response(request, 200, accountPage(), mapOf("Set-Cookie" to listOf(
                    "HOME=checked; Path=/",
                    "SUB=old; Domain=.weibo.com; Path=/",
                    "XSRF-TOKEN=old; Domain=.weibo.com; Path=/",
                )))
            }
        })
        val checking = async(start = CoroutineStart.UNDISPATCHED) { client.checkLoginState("123") }
        entered.await()
        client.fetchPostDetail("fixture")
        assertTrue(client.exportCookieHeader().contains("SUB=rotated"))
        release.complete(Unit)
        assertEquals("123", checking.await().account?.userId)
        val header = client.exportCookieHeader()
        assertTrue(header.contains("SUB=rotated"))
        assertTrue(header.contains("HOME=checked"))
        assertFalse(header.contains("XSRF-TOKEN="))
    }

    @Test
    fun `SSO commit preserves unrelated concurrent API cookies`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long; SUB=old"), textTransport = { request ->
            when (request.uri().host) {
                "login.sina.com.cn" -> {
                    entered.complete(Unit)
                    release.await()
                    response(request, 302, headers = mapOf("Location" to listOf("https://passport.weibo.com/sso/crossdomain?ticket=test")))
                }
                "passport.weibo.com" -> response(request, 302, headers = mapOf(
                    "Set-Cookie" to listOf("SUB=renewed; Domain=.weibo.com; Path=/"),
                    "Location" to listOf("https://weibo.com/"),
                ))
                else -> if (request.uri().path == "/ajax/statuses/show") {
                    response(request, 200, "{}", mapOf("Set-Cookie" to listOf(
                        "API=latest; Path=/",
                        "SUB=rotated; Domain=.weibo.com; Path=/",
                    )))
                } else response(request, 200, accountPage())
            }
        })
        val refreshing = async(start = CoroutineStart.UNDISPATCHED) { client.refreshLoginSession("123") }
        entered.await()
        client.fetchPostDetail("fixture")
        release.complete(Unit)
        assertEquals("123", refreshing.await().account?.userId)
        val header = client.exportCookieHeader()
        assertTrue(header.contains("SUB=renewed"))
        assertTrue(header.contains("API=latest"))
    }

    @Test
    fun `late responses from pre renewal requests cannot delete the renewed session`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long; SUB=old"), textTransport = { request ->
            when (request.uri().host) {
                "login.sina.com.cn" -> response(request, 302, headers = mapOf(
                    "Location" to listOf("https://passport.weibo.com/sso/crossdomain?ticket=test"),
                ))
                "passport.weibo.com" -> response(request, 302, headers = mapOf(
                    "Set-Cookie" to listOf("SUB=renewed; Domain=.weibo.com; Path=/"),
                    "Location" to listOf("https://weibo.com/"),
                ))
                else -> if (request.uri().path == "/ajax/statuses/show") {
                    if (request.headers().firstValue("Cookie").orElse("").contains("SUB=old")) {
                        entered.complete(Unit)
                        release.await()
                        response(request, 200, "{}", mapOf("Set-Cookie" to listOf("SUB=; Domain=.weibo.com; Path=/; Max-Age=0")))
                    } else {
                        response(request, 200, "{}", mapOf("Set-Cookie" to listOf("SUB=latest; Domain=.weibo.com; Path=/")))
                    }
                } else response(request, 200, accountPage())
            }
        })
        val oldRequest = async(start = CoroutineStart.UNDISPATCHED) { client.fetchPostDetail("fixture") }
        entered.await()
        assertEquals("123", client.refreshLoginSession("123").account?.userId)
        release.complete(Unit)
        oldRequest.await()
        assertTrue(client.exportCookieHeader().contains("SUB=renewed"))
        client.fetchPostDetail("fixture")
        assertTrue(client.exportCookieHeader().contains("SUB=latest"))
    }

    @Test
    fun `unconfigured read requests never reach transport`() = runBlocking {
        var requests = 0
        val client = WeiboClient(WeiboPublisherConfig(), textTransport = { requests++; response(it, 200) })
        client.checkLoginState()
        assertFailsWith<WeiboLoginException> { client.fetchPostDetail("fixture") }
        assertFailsWith<WeiboLoginException> { client.fetchHomeAccount() }
        assertEquals(0, requests)
    }

    @Test
    fun `API login redirects trigger recovery but server failures never count as expired credentials`() = runBlocking {
        val client = WeiboClient(WeiboPublisherConfig(cookie = "SUB=old"), textTransport = { request ->
            response(request, 302, headers = mapOf("Location" to listOf("https://login.sina.com.cn/sso/login.php?ticket=secret")))
        })
        val redirect = assertFailsWith<WeiboLoginException> { client.fetchPostDetail("fixture") }
        assertFalse(redirect.message.orEmpty().contains("secret"))
        for (status in listOf(429, 500, 503)) {
            val failure = assertFailsWith<WeiboApiException> {
                client.parseHttpJsonResponse(status, "/ajax/statuses/show", "login service unavailable")
            }
            assertFalse(failure is WeiboLoginException)
        }
    }

    @Test
    fun `QR crossdomain cookies including ALC survive restart`() = runBlocking {
        val client = WeiboClient(WeiboPublisherConfig(), textTransport = { request ->
            when (request.uri().host) {
                "passport.weibo.com" -> response(request, 302, headers = mapOf(
                    "Set-Cookie" to listOf("SUB=qr; Domain=.weibo.com; Path=/; HttpOnly"),
                    "Location" to listOf("https://login.sina.com.cn/sso/v2/crossdomain?ticket=qr"),
                ))
                "login.sina.com.cn" -> response(request, 302, headers = mapOf(
                    "Set-Cookie" to listOf("ALC=qr-long; Domain=.login.sina.com.cn; Path=/; Secure; HttpOnly"),
                    "Location" to listOf("https://passport.weibo.cn/sso/crossdomain?ticket=qr"),
                ))
                "passport.weibo.cn" -> response(request, 302, headers = mapOf(
                    "Set-Cookie" to listOf("SUB=qr-mobile; Domain=.weibo.cn; Path=/"),
                    "Location" to listOf("https://weibo.com/"),
                ))
                else -> response(request, 200, accountPage())
            }
        })
        assertEquals("123", client.completeQrLogin(WeiboQrLoginPollSnapshot(
            20_000_000, "ok", "https://passport.weibo.com/sso/v2/login?qrid=fixture",
        )).account?.userId)
        val restored = WeiboCookieJar(client.exportCookiesJson())
        assertTrue(restored.canRefresh())
        assertTrue(restored.headerFor(URI("https://login.sina.com.cn/sso/login.php")).contains("ALC=qr-long"))
        assertTrue(restored.headerFor(URI("https://weibo.com/")).contains("SUB=qr"))
    }

    @Test
    fun `timeouts and server failures are transient and candidate is discarded`() = runBlocking {
        val slow = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long; SUB=old"), textTransport = {
            delay(1_000)
            response(it, 200)
        }, sessionTimeoutMillis = 20)
        assertEquals(WeiboSessionFailure.TRANSIENT, assertFailsWith<WeiboSessionException> { slow.refreshLoginSession() }.failure)
        assertTrue(slow.exportCookieHeader().contains("SUB=old"))
        for (status in listOf(429, 500, 503)) {
            val failed = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long"), textTransport = { response(it, status) })
            assertEquals(WeiboSessionFailure.TRANSIENT, assertFailsWith<WeiboSessionException> { failed.refreshLoginSession() }.failure)
        }
    }

    @Test
    fun `renewal requires newly issued session and limits redirect hops`() = runBlocking {
        val stale = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long; SUB=old"), textTransport = { request ->
            if (request.uri().host == "login.sina.com.cn") response(request, 302, headers = mapOf(
                "Location" to listOf("https://weibo.com/"),
            )) else response(request, 200, accountPage())
        })
        assertEquals(WeiboSessionFailure.LOGIN_REQUIRED, assertFailsWith<WeiboSessionException> { stale.refreshLoginSession() }.failure)
        var requests = 0
        val redirects = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long"), textTransport = { request ->
            requests++
            response(request, 302, headers = mapOf("Location" to listOf("https://login.sina.com.cn/sso/login.php?hop=$requests")))
        })
        assertEquals(WeiboSessionFailure.PROTOCOL, assertFailsWith<WeiboSessionException> { redirects.refreshLoginSession() }.failure)
        assertEquals(10, requests)
    }

    @Test
    fun `account mismatch rejects fresh SSO and homepage cookies`() = runBlocking {
        val client = WeiboClient(WeiboPublisherConfig(cookie = "ALC=long; SUB=old"), textTransport = { request ->
            when (request.uri().host) {
                "login.sina.com.cn" -> response(request, 302, headers = mapOf(
                    "Location" to listOf("https://passport.weibo.com/sso/crossdomain?ticket=test"),
                ))
                "passport.weibo.com" -> response(request, 302, headers = mapOf(
                    "Set-Cookie" to listOf("SUB=different; Domain=.weibo.com; Path=/"),
                    "Location" to listOf("https://weibo.com/"),
                ))
                else -> response(request, 200, accountPage("456"), mapOf(
                    "Set-Cookie" to listOf("SUB=different; Domain=.weibo.com; Path=/"),
                ))
            }
        })
        assertEquals(WeiboSessionFailure.ACCOUNT_CHANGED, assertFailsWith<WeiboSessionException> { client.refreshLoginSession("123") }.failure)
        assertEquals(WeiboSessionFailure.ACCOUNT_CHANGED, assertFailsWith<WeiboSessionException> { client.checkLoginState("123") }.failure)
        assertTrue(client.exportCookieHeader().contains("SUB=old"))
    }

    private fun accountPage(uid: String = "123"): String = """<script>window.${'$'}CONFIG={"user":{"idstr":"$uid","screen_name":"test"}}</script>"""

    private fun response(request: HttpRequest, status: Int, body: String = "", headers: Map<String, List<String>> = emptyMap()): HttpResponse<String> = object : HttpResponse<String> {
        override fun statusCode(): Int = status
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(headers) { _, _ -> true }
        override fun body(): String = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
