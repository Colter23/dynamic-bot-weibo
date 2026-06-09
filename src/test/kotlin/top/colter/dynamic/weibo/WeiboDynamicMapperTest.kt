package top.colter.dynamic.weibo

import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PollBlock
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.TextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WeiboDynamicMapperTest {
    private val mapper = WeiboDynamicMapper()
    private val fallbackPublisher = Publisher(
        id = 1,
        key = PublisherKey.of(WEIBO_PLATFORM_ID, PublisherKind.USER, "10001"),
        name = "测试用户",
        avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR),
        createTime = 1,
        createUser = 1,
    )

    @Test
    fun `map html mentions topics and links to rich content nodes`() {
        val post = testPost(
            rawText = "<a href=\"/n/测试用户\" usercard=\"name=@测试用户\">@测试用户</a>" +
                "<a href=\"//s.weibo.com/weibo?q=%23%E8%AF%9D%E9%A2%98%23\">#话题#</a>" +
                "正文<br />" +
                "<a target=\"_blank\" href=\"https://example.com/page\"><img title=\"http://t.cn/demo\" />网页链接</a>",
            text = "@测试用户 #话题# 正文\n网页链接",
        )

        val content = post.mapTextContent()

        assertEquals("@测试用户#话题#正文\n网页链接", content.plainText.trim())
        val mention = assertIs<DynamicContentNodeMention>(content.nodes.first { it.text == "@测试用户" })
        assertEquals("https://weibo.com/n/测试用户", mention.url)
        val topic = assertIs<DynamicContentNodeTag>(content.nodes.first { it.text == "#话题#" })
        assertEquals("话题", topic.externalId)
        assertEquals("https://s.weibo.com/weibo?q=%23%E8%AF%9D%E9%A2%98%23", topic.url)
        val link = assertIs<DynamicContentNodeLink>(content.nodes.first { it.text == "网页链接" })
        assertEquals("https://example.com/page", link.url)
    }

    @Test
    fun `map super topic anchor image title as topic node`() {
        val post = testPost(
            rawText = "<a target=\"_blank\" href=\"https://weibo.com/p/100808demo\">" +
                "<img class=\"icon-link\" title=\"#崩坏星穹铁道[超话]#\" src=\"https://example.com/topic.png\"/>" +
                "崩坏星穹铁道超话</a> 正文",
            text = "#崩坏星穹铁道[超话]# 正文",
        )

        val content = post.mapTextContent()

        val topic = assertIs<DynamicContentNodeTag>(content.nodes.first())
        assertEquals("#崩坏星穹铁道[超话]#", topic.text)
        assertEquals("崩坏星穹铁道[超话]", topic.externalId)
        assertEquals("https://weibo.com/p/100808demo", topic.url)
    }

    @Test
    fun `map plain mentions topics and urls to rich content nodes`() {
        val post = testPost(
            rawText = "关注 @测试用户 参与 #微博话题# https://example.com/post",
            text = "关注 @测试用户 参与 #微博话题# https://example.com/post",
        )

        val content = post.mapTextContent()

        assertTrue(content.nodes.any { it is DynamicContentNodeMention && it.text == "@测试用户" })
        assertTrue(content.nodes.any { it is DynamicContentNodeTag && it.text == "#微博话题#" })
        assertTrue(content.nodes.any { it is DynamicContentNodeLink && it.text == "https://example.com/post" })
    }

    @Test
    fun `map standalone html image as emoji node`() {
        val post = testPost(
            rawText = "表情<img alt=\"[允悲]\" src=\"https://face.t.sinajs.cn/t4/appstyle/expression/ext/normal/65/yunbei.png\" />结束",
            text = "表情[允悲]结束",
        )

        val content = post.mapTextContent()

        val emoji = assertIs<DynamicContentNodeEmoji>(content.nodes.first { it.text == "[允悲]" })
        assertEquals("https://face.t.sinajs.cn/t4/appstyle/expression/ext/normal/65/yunbei.png", emoji.image?.uri)
    }

    @Test
    fun `map media card kind without collapsing to link`() {
        val post = testPost(
            rawText = "正文",
            text = "正文",
            card = WeiboMediaCardSnapshot(
                kind = WeiboMediaCardKind.ARTICLE,
                id = "article-1",
                title = "文章标题",
                description = "文章摘要",
                coverUrl = "https://example.com/article.jpg",
                url = "https://weibo.com/ttarticle/x/m/show/id/1",
            ),
        )

        val card = assertIs<MediaCardBlock>(post.mapBlocks().single { it is MediaCardBlock })

        assertEquals(DynamicMediaCardKind.ARTICLE, card.card.kind)
        assertEquals("weibo.page_info:article", card.card.sourceKind)
    }

    @Test
    fun `map additional media cards and image badges`() {
        val post = testPost(
            rawText = "混合媒体",
            text = "混合媒体",
            additionalCards = listOf(
                WeiboMediaCardSnapshot(
                    kind = WeiboMediaCardKind.VIDEO,
                    id = "video-1",
                    title = "混合视频",
                    info = "1.2万次观看",
                    coverUrl = "https://example.com/video.jpg",
                    mediaUrl = "https://example.com/video.mp4",
                )
            ),
            pictures = listOf(
                WeiboImageSnapshot(
                    url = "https://example.com/gif.jpg",
                    badge = "GIF",
                )
            ),
        )

        val blocks = post.mapBlocks()
        val card = assertIs<MediaCardBlock>(blocks.single { it is MediaCardBlock })
        val image = assertIs<ImageGridBlock>(blocks.single { it is ImageGridBlock })

        assertEquals(DynamicMediaCardKind.VIDEO, card.card.kind)
        assertEquals("1.2万次观看", card.card.info)
        assertEquals("GIF", image.images.single().badge)
    }

    @Test
    fun `map poll snapshot to poll block`() {
        val post = testPost(
            rawText = "投票正文",
            text = "投票正文",
            poll = WeiboPollSnapshot(
                id = "vote-1",
                title = "选一个？",
                status = WeiboPollStatus.OPEN,
                link = "https://vote.weibo.com/h5/index/index?vote_id=vote-1",
                options = listOf(
                    WeiboPollOptionSnapshot(id = "1", text = "A", votes = 12, displayVotes = "12 票"),
                    WeiboPollOptionSnapshot(id = "2", text = "B", votes = 3, displayVotes = "3 票"),
                ),
            ),
        )

        val poll = assertIs<PollBlock>(post.mapBlocks().single { it is PollBlock })

        assertEquals("vote-1", poll.id)
        assertEquals("选一个？", poll.title)
        assertEquals(listOf("A", "B"), poll.options.map { it.text })
        assertEquals(12, poll.options.first().votes)
    }

    @Test
    fun `map retweeted status as origin reference`() {
        val post = testPost(
            rawText = "转发正文",
            text = "转发正文",
            reposted = testPost(
                rawText = "原文",
                text = "原文",
                postId = "origin",
                userId = "20002",
            ),
        )

        val repost = assertIs<RepostBlock>(post.mapBlocks().single { it is RepostBlock })

        assertEquals(DynamicReferenceKind.ORIGIN, repost.referenceKind)
        assertEquals("origin", repost.key.externalId)
    }

    @Test
    fun `map labels and forward metric display`() {
        val post = testPost(
            rawText = "正文",
            text = "正文",
            source = "微博网页版",
            regionName = "发布于 上海",
            isTop = true,
            badge = "热门",
            metrics = WeiboPostMetrics(
                reposts = 12_345,
                comments = 67,
                likes = 100_000_000,
            ),
        )

        val payload = post.mapPayload()

        assertEquals(
            listOf("置顶", "热门", "微博网页版", "发布于 上海"),
            payload.labels.map { it.text },
        )
        assertEquals(DynamicLabelKind.BADGE, payload.labels.first().kind)
        assertEquals("forward", payload.metrics.first().key)
        assertEquals("1.2万", payload.metrics.first().display)
        assertEquals("1亿", payload.metrics.last().display)
    }

    @Test
    fun `map unknown repost publisher without fallback identity`() {
        val post = testPost(
            rawText = "转发正文",
            text = "转发正文",
            reposted = testPost(
                rawText = "原微博已不可见",
                text = "原微博已不可见",
                postId = "blocked",
                userId = "__unknown__",
                screenName = "未知微博用户",
                avatarUrl = null,
            ),
        )

        val repost = assertIs<RepostBlock>(post.mapBlocks().single { it is RepostBlock })
        val embedded = assertNotNull(repost.embedded)

        assertEquals("__unknown__", embedded.publisher.externalId)
        assertEquals("未知微博用户", embedded.publisher.name)
        assertEquals("", embedded.publisher.avatar.uri)
    }

    private fun testPost(
        rawText: String,
        text: String,
        postId: String = "R1CXrAEh5",
        userId: String = "10001",
        screenName: String? = "测试用户",
        avatarUrl: String? = "https://example.com/avatar.png",
        source: String? = null,
        regionName: String? = null,
        isTop: Boolean = false,
        badge: String? = null,
        pictures: List<WeiboImageSnapshot> = emptyList(),
        card: WeiboMediaCardSnapshot? = null,
        additionalCards: List<WeiboMediaCardSnapshot> = emptyList(),
        poll: WeiboPollSnapshot? = null,
        reposted: WeiboPostSnapshot? = null,
        metrics: WeiboPostMetrics = WeiboPostMetrics(),
    ): WeiboPostSnapshot {
        return WeiboPostSnapshot(
            postId = postId,
            userId = userId,
            screenName = screenName,
            avatarUrl = avatarUrl,
            text = text,
            rawText = rawText,
            createdAtEpochSeconds = 1_780_000_000,
            url = postLink(userId, postId),
            source = source,
            regionName = regionName,
            isTop = isTop,
            badge = badge,
            pictures = pictures,
            card = card,
            additionalCards = additionalCards,
            poll = poll,
            reposted = reposted,
            metrics = metrics,
        )
    }

    private fun WeiboPostSnapshot.mapPayload() =
        assertIs<DynamicPayload>(mapper.map(this, fallbackPublisher)?.payload)

    private fun WeiboPostSnapshot.mapTextContent() =
        assertNotNull(
            mapPayload()
                .blocks
                .filterIsInstance<TextBlock>()
                .singleOrNull()
                ?.content
        )

    private fun WeiboPostSnapshot.mapBlocks() =
        mapPayload().blocks
}
