package top.colter.dynamic.weibo

import java.net.URI
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeiboCookieJarTest {

    @Test
    fun `jar should keep cookies out of the automatic http client header`() {
        val jar = WeiboCookieJar()

        assertTrue(jar.get(URI.create(WEIBO_HOME), emptyMap()).isEmpty())
    }

    @Test
    fun `jar should store set-cookie response and not mark it removed`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        jar.put(URI.create(WEIBO_HOME), mapOf("Set-Cookie" to listOf("SUB=new; Path=/; HttpOnly")))

        assertTrue(jar.removedCookieNames.isEmpty())
        assertEquals("SUB=new; XSRF-TOKEN=token", client.exportCookieHeader())
    }

    @Test
    fun `max age zero should mark cookie as removed`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        jar.put(
            URI.create(WEIBO_HOME),
            mapOf("Set-Cookie" to listOf("SUB=; Path=/; Domain=.weibo.com; Max-Age=0")),
        )

        assertEquals(setOf("SUB"), jar.removedCookieNames)
        assertEquals("XSRF-TOKEN=token", client.exportCookieHeader())
    }

    @Test
    fun `past expires should mark cookie as removed`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        jar.put(
            URI.create(WEIBO_HOME),
            mapOf("Set-Cookie" to listOf("SUB=; Path=/; Domain=.weibo.com; Expires=Thu, 01 Jan 1970 00:00:00 GMT")),
        )

        assertEquals(setOf("SUB"), jar.removedCookieNames)
        assertEquals("XSRF-TOKEN=token", client.exportCookieHeader())
    }

    @Test
    fun `past expires without domain should still drop the configured value`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        jar.put(
            URI.create(WEIBO_HOME),
            mapOf("Set-Cookie" to listOf("SUB=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")),
        )

        assertEquals("XSRF-TOKEN=token", client.exportCookieHeader())
    }

    @Test
    fun `setting the cookie again should clear the removed mark`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")
        jar.put(URI.create(WEIBO_HOME), mapOf("Set-Cookie" to listOf("SUB=; Path=/; Max-Age=0")))
        assertEquals("XSRF-TOKEN=token", client.exportCookieHeader())

        jar.put(URI.create(WEIBO_HOME), mapOf("Set-Cookie" to listOf("SUB=revived; Path=/")))

        assertTrue(jar.removedCookieNames.isEmpty())
        assertEquals("SUB=revived; XSRF-TOKEN=token", client.exportCookieHeader())
    }

    @Test
    fun `configured cookie should be used until the server sends its own`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        assertEquals("SUB=old; XSRF-TOKEN=token", client.exportCookieHeader())
        assertTrue(jar.cookieManager.cookieStore.cookies.isEmpty())
    }

    @Test
    fun `server cookie should win over the configured value with the same name`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        jar.put(URI.create(WEIBO_HOME), mapOf("Set-Cookie" to listOf("SUB=new; Path=/; Domain=.weibo.com")))

        assertEquals("SUB=new; XSRF-TOKEN=token", client.exportCookieHeader())
    }

    @Test
    fun `server cookie should not leave a duplicate store entry behind`() {
        val (_, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        jar.put(URI.create(WEIBO_HOME), mapOf("Set-Cookie" to listOf("SUB=new; Path=/; Domain=.weibo.com")))

        assertEquals(listOf("SUB=new"), jar.cookieManager.cookieStore.cookies.map { "${it.name}=${it.value}" })
    }

    @Test
    fun `host-only removal from passport host should not drop the weibo value`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        // passport 主机上没有 Domain 属性的过期 cookie 只作用于 passport 自己
        jar.put(
            URI.create("https://passport.weibo.com"),
            mapOf("Set-Cookie" to listOf("SUB=; Path=/; Max-Age=0")),
        )

        assertTrue(jar.removedCookieNames.isEmpty())
        assertEquals("SUB=old; XSRF-TOKEN=token", client.exportCookieHeader())
    }

    @Test
    fun `explicit weibo domain removal still applies from other hosts`() {
        val (client, jar) = jarBackedClient("SUB=old; XSRF-TOKEN=token")

        jar.put(
            URI.create("https://passport.weibo.com"),
            mapOf("Set-Cookie" to listOf("SUB=; Path=/; Domain=.weibo.com; Max-Age=0")),
        )

        assertEquals(setOf("SUB"), jar.removedCookieNames)
        assertEquals("XSRF-TOKEN=token", client.exportCookieHeader())
    }

    @Test
    fun `host matching cookie should win over cross domain duplicate`() {
        val (client, jar) = jarBackedClient("")

        jar.put(
            URI.create("https://login.sina.com.cn"),
            mapOf("Set-Cookie" to listOf("SUB=sina-value; Path=/; Domain=.sina.com.cn")),
        )
        jar.put(
            URI.create(WEIBO_HOME),
            mapOf("Set-Cookie" to listOf("SUB=weibo-value; Path=/; Domain=.weibo.com")),
        )

        assertEquals("SUB=weibo-value", client.exportCookieHeader())
    }

    private fun jarBackedClient(cookie: String): Pair<WeiboClient, WeiboCookieJar> {
        val jar = WeiboCookieJar()
        val httpClient = HttpClient.newBuilder().cookieHandler(jar).build()
        val client = WeiboClient(
            config = WeiboPublisherConfig(cookie = cookie),
            httpClient = httpClient,
        )
        return client to jar
    }

    private companion object {
        private const val WEIBO_HOME: String = "https://weibo.com"
    }
}
