package top.colter.dynamic.weibo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.colter.dynamic.core.plugin.FollowActionStatus
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WeiboClientHarParserTest {
    private val client = WeiboClient(WeiboPublisherConfig())

    @Test
    fun `parse profile info from har`() {
        val response = harResponse("个人主页.har", "/ajax/profile/info") ?: return

        val profile = client.parsePublisherInfoResponse(response)

        assertNotNull(profile)
        assertTrue(profile.userId.isNotBlank())
        assertTrue(profile.screenName.isNotBlank())
        assertNotNull(profile.avatarUrl)
    }

    @Test
    fun `parse profile cover uses first semicolon separated url`() {
        val profile = client.parsePublisherInfoResponse(
            """
            {
              "ok": 1,
              "data": {
                "user": {
                  "idstr": "5977716744",
                  "screen_name": "测试用户",
                  "cover_image_phone": "https://wx2.sinaimg.cn/crop.0.0.640.640.640/first.jpg;https://wx3.sinaimg.cn/crop.0.0.640.640.640/second.jpg"
                }
              }
            }
            """.trimIndent(),
        )

        assertNotNull(profile)
        assertEquals("https://wx2.sinaimg.cn/crop.0.0.640.640.640/first.jpg", profile.coverUrl)
    }

    @Test
    fun `parse profile detail from har`() {
        val response = harResponse("个人主页.har", "/ajax/profile/detail") ?: return

        val profile = client.parsePublisherDetailResponse("5977716744", response)

        assertNotNull(profile)
        assertTrue(profile.userId.isNotBlank())
        assertTrue(profile.description?.isNotBlank() == true || profile.location?.isNotBlank() == true)
    }

    @Test
    fun `parse user timeline from har`() {
        val response = harResponse("个人主页.har", "/ajax/statuses/mymblog") ?: return

        val page = client.parseUserTimelineResponse(response)

        assertTrue(page.posts.isNotEmpty())
        assertTrue(page.posts.any { it.text.isNotBlank() })
        assertTrue(page.posts.any { it.reposted != null || it.pictures.isNotEmpty() || it.card != null })
    }

    @Test
    fun `parse paged user timeline from har`() {
        val responses = harResponses("1.个人微博分页.har", "/ajax/statuses/mymblog")
        if (responses.isEmpty()) return

        val pages = responses.map { client.parseUserTimelineResponse(it) }

        assertTrue(pages.size >= 3)
        assertTrue(pages.all { it.posts.isNotEmpty() })
        assertTrue(pages.first().nextCursor?.isNotBlank() == true)
    }

    @Test
    fun `parse status detail from har`() {
        val response = harResponse("动态详情.har", "/ajax/statuses/show") ?: return

        val post = client.parsePostDetailResponse(response)

        assertNotNull(post)
        assertTrue(post.postId.isNotBlank())
        assertTrue(post.text.isNotBlank())
        assertNotNull(post.card)
    }

    @Test
    fun `parse video card uses media title and best playback url`() {
        val post = client.parsePostDetailResponse(
            """
            {
              "ok": 1,
              "idstr": "5303860617740351",
              "mblogid": "R1CXrAEh5",
              "created_at": "Mon Jun 08 20:00:00 +0800 2026",
              "user": {"idstr": "7643376782", "screen_name": "测试用户"},
              "text": "正文",
              "text_raw": "正文",
              "page_info": {
                "type": "11",
                "object_type": "video",
                "object_id": "1034:5303860617740351",
                "page_title": "测试用户的微博视频",
                "content1": "测试用户的微博视频",
                "page_pic": "https://example.com/cover.jpg",
                "short_url": "http://t.cn/video",
                "media_info": {
                  "video_title": "真正的视频标题",
                  "online_users": "9.9万次观看",
                  "duration": 104,
                  "playback_list": [
                    {"meta": {"quality_index": 480}, "play_info": {"url": "https://example.com/480.mp4"}},
                    {"meta": {"quality_index": 1080}, "play_info": {"url": "https://example.com/1080.mp4"}}
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertNotNull(post)
        assertEquals(WeiboMediaCardKind.VIDEO, post.card?.kind)
        assertEquals("真正的视频标题", post.card?.title)
        assertEquals("9.9万次观看", post.card?.info)
        assertEquals("https://example.com/1080.mp4", post.card?.mediaUrl)
    }

    @Test
    fun `parse mixed media cards and gif badges from har`() {
        val responses = harResponses("1.个人微博分页.har", "/ajax/statuses/mymblog")
        if (responses.isEmpty()) return

        val posts = responses.flatMap { client.parseUserTimelineResponse(it).posts }
        val mixed = posts.firstOrNull { it.postId == "R0CcToodL" } ?: return

        assertEquals(3, mixed.pictures.size)
        assertEquals(1, mixed.additionalCards.size)
        assertEquals(WeiboMediaCardKind.VIDEO, mixed.additionalCards.single().kind)
        assertTrue(mixed.additionalCards.single().mediaUrl?.isNotBlank() == true)
        assertTrue(posts.any { post -> post.pictures.any { it.badge == "GIF" } })
    }

    @Test
    fun `parse unavailable retweeted status as unknown publisher`() {
        val post = client.parsePostDetailResponse(
            """
            {
              "ok": 1,
              "idstr": "5303860617740351",
              "mblogid": "R1CXrAEh5",
              "created_at": "Mon Jun 08 20:00:00 +0800 2026",
              "user": {"idstr": "7643376782", "screen_name": "测试用户"},
              "text": "转发正文",
              "text_raw": "转发正文",
              "retweeted_status": {
                "idstr": "5224275604932935",
                "mblogid": "QabwckHht",
                "created_at": "Tue Oct 21 19:57:09 +0800 2025",
                "user": null,
                "text": "抱歉，根据作者设置的微博可见时间范围，此微博已不可见。 ​​​",
                "text_raw": "抱歉，根据作者设置的微博可见时间范围，此微博已不可见。 ​​​"
              }
            }
            """.trimIndent(),
        )

        val reposted = assertNotNull(post?.reposted)
        assertEquals("__unknown__", reposted.userId)
        assertEquals("未知微博用户", reposted.screenName)
        assertEquals("https://weibo.com/detail/QabwckHht", reposted.url)
        assertTrue(reposted.text.contains("已不可见"))
    }

    @Test
    fun `parse vote page info as poll snapshot`() {
        val post = client.parsePostDetailResponse(
            """
            {
              "ok": 1,
              "idstr": "5307595717088888",
              "mblogid": "R2test",
              "created_at": "Mon Jun 08 20:00:00 +0800 2026",
              "user": {"idstr": "2028810631", "screen_name": "测试用户"},
              "text": "投票正文",
              "text_raw": "投票正文",
              "page_info": {
                "type": "23",
                "object_type": "hudongvote",
                "page_title": "题目",
                "page_url": "https://vote.weibo.com/h5/index/index?vote_id=vote-1",
                "object_id": "1022:vote-1",
                "card_info": {
                  "vote_object": {
                    "id": "vote-1",
                    "content": "选一个？",
                    "state": 1,
                    "expire_date": 4102444800,
                    "vote_list": [
                      {"id": "1", "content": "A", "part_num": "12"},
                      {"id": "2", "content": "B", "part_num": "3"}
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertNotNull(post)
        assertEquals(null, post.card)
        assertEquals("vote-1", post.poll?.id)
        assertEquals("选一个？", post.poll?.title)
        assertEquals(WeiboPollStatus.OPEN, post.poll?.status)
        assertEquals(listOf("A", "B"), post.poll?.options?.map { it.text })
    }

    @Test
    fun `parse long text from har`() {
        val response = harResponse("2.长文微博详情.har", "/ajax/statuses/longtext") ?: return

        val longText = client.parseLongTextResponse(response)

        assertNotNull(longText)
        assertTrue(longText.content.isNotBlank())
    }

    @Test
    fun `parse login state from har`() {
        val response = harResponse("3.当前登录账号状态.har", "/ajax/getNavConfig") ?: return

        assertTrue(client.parseLoginStateResponse(response))
    }

    @Test
    fun `parse current account from weibo home html`() {
        val response = harResponse("微博首页.har", "/") ?: return

        val account = client.parseHomeAccountResponse(response)

        assertNotNull(account)
        assertEquals("5977716744", account.userId)
        assertEquals("Colter_null", account.name)
        assertNotNull(account.avatar)
    }

    @Test
    fun `parse current uid from mobile config`() {
        val response = harResponse("移动端.har", "/api/config") ?: return

        assertEquals("5977716744", client.parseMobileConfigUidResponse(response))
    }

    @Test
    fun `login state requires explicit ok`() {
        assertFalse(client.parseLoginStateResponse("""{"data":{"uid":"5977716744"}}"""))
        assertFalse(client.parseLoginStateResponse("""{"ok":0,"message":"未登录"}"""))
    }

    @Test
    fun `classify login html response as login failure`() {
        assertFailsWith<WeiboLoginException> {
            client.parseHttpJsonResponse(
                statusCode = 200,
                path = "/ajax/getNavConfig",
                body = "<html><title>微博登录</title><a href=\"https://passport.weibo.com/\">登录</a></html>",
            )
        }
    }

    @Test
    fun `parse follow action responses from har`() {
        val follow = harResponse("4.关注.har", "/ajax/friendships/create") ?: return
        val unfollow = harResponse("4.关注.har", "/ajax/friendships/destory") ?: return

        val followResult = client.parseFollowActionResponse(follow, expectedFollowing = true)
        val unfollowResult = client.parseFollowActionResponse(unfollow, expectedFollowing = false)

        assertTrue(followResult.status == FollowActionStatus.DONE)
        assertTrue(unfollowResult.status == FollowActionStatus.DONE)
    }

    @Test
    fun `parse follow groups from har`() {
        val assigned = harResponse("分组.har", "/ajax/profile/getGroupList")
            ?: harResponse("4.关注.har", "/ajax/profile/getGroupList")
            ?: return
        val available = harResponse("分组.har", "/ajax/profile/getGroups")
            ?: harResponse("4.关注.har", "/ajax/profile/getGroups")
            ?: return
        val setGroup = harResponse("分组.har", "/ajax/profile/setGroup")
            ?: harResponse("4.关注.har", "/ajax/profile/setGroup")
            ?: return

        val assignedGroups = client.parseAssignedFollowGroupsResponse(assigned)
        val availableGroups = client.parseAvailableFollowGroupsResponse(available)

        assertTrue(assignedGroups.isNotEmpty())
        assertTrue(availableGroups.isNotEmpty())
        assertTrue(availableGroups.any { it.name.isNotBlank() && it.id.isNotBlank() })
        assertTrue(client.parseSetFollowGroupsResponse(setGroup))
    }

    @Test
    fun `parse create follow group from har`() {
        val created = harResponses("分组.har", "/ajax/profile/createGroup")
            .mapNotNull { client.parseCreateFollowGroupResponse(it) }
            .firstOrNull()
            ?: return

        assertEquals("游戏2", created.name)
        assertTrue(created.id.isNotBlank())
    }

    @Test
    fun `parse friend timeline from har`() {
        val response = harResponse("最新微博.har", "/ajax/feed/friendstimeline") ?: return

        val page = client.parseFriendTimelineResponse(response)

        assertTrue(page.posts.isNotEmpty())
        assertTrue(page.posts.any { it.pictures.isNotEmpty() || it.card != null })
    }

    @Test
    fun `build friend timeline list id from login uid`() {
        val client = WeiboClient(WeiboPublisherConfig(loginUserId = "5977716744"))

        assertEquals("110005977716744", client.configuredFriendTimelineListId())
    }

    @Test
    fun `parse default friend timeline list id from har`() {
        val response = harResponse("最新微博.har", "/ajax/feed/allGroups") ?: return

        assertEquals("110005977716744", client.parseDefaultFriendTimelineListIdResponse(response))
    }

    @Test
    fun `export cookie should merge runtime cookies over configured cookie`() {
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        cookieManager.cookieStore.add(
            URI.create("https://weibo.com"),
            HttpCookie("SUB", "new").apply {
                domain = ".weibo.com"
                path = "/"
            },
        )
        cookieManager.cookieStore.add(
            URI.create("https://weibo.com"),
            HttpCookie("fresh", "1").apply {
                domain = ".weibo.com"
                path = "/"
            },
        )
        val httpClient = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .build()
        val client = WeiboClient(
            config = WeiboPublisherConfig(cookie = "SUB=old; XSRF-TOKEN=token"),
            httpClient = httpClient,
        )

        assertEquals("SUB=new; XSRF-TOKEN=token; fresh=1", client.exportCookieHeader())
    }

    private fun harResponse(fileName: String, path: String): String? {
        return harResponses(fileName, path).firstOrNull()
    }

    private fun harResponses(fileName: String, path: String): List<String> {
        val text = javaClass.classLoader
            .getResource("weibo-har/$fileName")
            ?.readText()
            ?: return emptyList()
        val root = HAR_JSON.parseToJsonElement(text).jsonObject
        val entries = root.getValue("log").jsonObject.getValue("entries").jsonArray
        return entries.mapNotNull { entry ->
            val item = entry.jsonObject
            val url = item.getValue("request")
                .jsonObject
                .getValue("url")
                .jsonPrimitive
                .content
            val requestPath = runCatching { URI.create(url).path }.getOrNull()
            if (requestPath != path) return@mapNotNull null
            item.getValue("response")
                .jsonObject
                .getValue("content")
                .jsonObject["text"]
                ?.jsonPrimitive
                ?.content
        }
    }

    private companion object {
        private val HAR_JSON: Json = Json { ignoreUnknownKeys = true }
    }
}
