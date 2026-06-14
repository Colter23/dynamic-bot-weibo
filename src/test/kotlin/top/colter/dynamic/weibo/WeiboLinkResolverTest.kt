package top.colter.dynamic.weibo

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WeiboLinkResolverTest {
    private val platformId = PlatformId.of(WEIBO_PLATFORM_ID)
    private val post = WeiboPostSnapshot(
        postId = "R1CXrAEh5",
        userId = "5977716744",
        screenName = "测试用户",
        avatarUrl = "https://example.com/avatar.png",
        text = "一条微博",
        createdAtEpochSeconds = 1_780_000_000,
        url = "https://weibo.com/5977716744/R1CXrAEh5",
    )
    private val publisher = PublisherInfo(
        key = PublisherKey.of(
            platformId = WEIBO_PLATFORM_ID,
            kind = PublisherKind.USER,
            externalId = "5977716744",
        ),
        name = "测试用户",
        avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR),
        banner = MediaRef("https://example.com/banner.png", MediaKind.COVER),
    )
    private val resolver = WeiboLinkResolver(
        platformId = platformId,
        gatewayProvider = { FakeGateway(post) },
        mapper = WeiboDynamicMapper(),
        publisherInfoResolver = { userId -> publisher.takeIf { it.externalId == userId } },
    )

    @Test
    fun `parse desktop dynamic links`() = runBlocking {
        val detail = assertNotNull(resolver.parseLink("https://weibo.com/detail/R1CXrAEh5"))
        val desktop = assertNotNull(resolver.parseLink("https://weibo.com/5977716744/R1CXrAEh5"))

        assertEquals(LinkKinds.DYNAMIC, detail.kind)
        assertEquals("R1CXrAEh5", detail.targetId)
        assertEquals("https://weibo.com/detail/R1CXrAEh5", detail.normalizedUrl)
        assertEquals(LinkKinds.DYNAMIC, desktop.kind)
        assertEquals("R1CXrAEh5", desktop.targetId)
    }

    @Test
    fun `parse user and mobile links`() = runBlocking {
        val user = assertNotNull(resolver.parseLink("https://weibo.com/u/5977716744"))
        val mobile = assertNotNull(resolver.parseLink("https://m.weibo.cn/status/R1CXrAEh5"))

        assertEquals(LinkKinds.USER, user.kind)
        assertEquals("5977716744", user.targetId)
        assertEquals("https://weibo.com/u/5977716744", user.normalizedUrl)
        assertEquals(LinkKinds.DYNAMIC, mobile.kind)
        assertEquals("R1CXrAEh5", mobile.targetId)
    }

    @Test
    fun `resolve dynamic link`() = runBlocking {
        val parsed = assertNotNull(resolver.parseLink("https://weibo.com/detail/R1CXrAEh5"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Dynamic)
        assertEquals("R1CXrAEh5", resolution.update.key.externalId)
        assertEquals("5977716744", resolution.update.publisher.externalId)
    }

    @Test
    fun `resolve user preview`() = runBlocking {
        val parsed = assertNotNull(resolver.parseLink("https://weibo.com/u/5977716744"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Preview)
        assertEquals(LinkKinds.USER, resolution.preview.kind)
        assertEquals("测试用户", resolution.preview.title)
        assertEquals("5977716744", resolution.preview.publisher?.externalId)
    }

    private class FakeGateway(
        private val post: WeiboPostSnapshot?,
    ) : WeiboGateway {
        override suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot? = null

        override suspend fun fetchPostDetail(postId: String): WeiboPostSnapshot? {
            return post?.takeIf { it.postId == postId }
        }
    }
}
