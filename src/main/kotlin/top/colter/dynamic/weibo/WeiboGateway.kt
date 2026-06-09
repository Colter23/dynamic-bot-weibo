package top.colter.dynamic.weibo

import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus

internal data class WeiboPublisherSnapshot(
    val userId: String,
    val screenName: String,
    val avatarUrl: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val location: String? = null,
    val verified: Boolean = false,
    val followersCount: Long? = null,
    val friendsCount: Long? = null,
    val statusesCount: Long? = null,
    val following: Boolean? = null,
)

internal data class WeiboPostSnapshot(
    val postId: String,
    val userId: String,
    val screenName: String? = null,
    val avatarUrl: String? = null,
    val text: String = "",
    val rawText: String = text,
    val createdAtEpochSeconds: Long,
    val url: String? = null,
    val source: String? = null,
    val regionName: String? = null,
    val isTop: Boolean = false,
    val badge: String? = null,
    val isLongText: Boolean = false,
    val pictures: List<WeiboImageSnapshot> = emptyList(),
    val card: WeiboMediaCardSnapshot? = null,
    val additionalCards: List<WeiboMediaCardSnapshot> = emptyList(),
    val poll: WeiboPollSnapshot? = null,
    val reposted: WeiboPostSnapshot? = null,
    val metrics: WeiboPostMetrics = WeiboPostMetrics(),
)

internal data class WeiboImageSnapshot(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val badge: String? = null,
    val alt: String? = null,
)

internal data class WeiboPostMetrics(
    val reposts: Long? = null,
    val comments: Long? = null,
    val likes: Long? = null,
)

internal data class WeiboLongTextSnapshot(
    val content: String,
    val rawContent: String = content,
)

internal data class WeiboMediaCardSnapshot(
    val kind: WeiboMediaCardKind = WeiboMediaCardKind.LINK,
    val id: String? = null,
    val title: String,
    val description: String = "",
    val info: String? = null,
    val coverUrl: String? = null,
    val mediaUrl: String? = null,
    val durationSeconds: Long? = null,
    val url: String? = null,
)

internal enum class WeiboMediaCardKind {
    VIDEO,
    ARTICLE,
    LIVE,
    PRODUCT,
    LINK,
}

internal data class WeiboPollSnapshot(
    val id: String? = null,
    val title: String,
    val options: List<WeiboPollOptionSnapshot> = emptyList(),
    val status: WeiboPollStatus = WeiboPollStatus.UNKNOWN,
    val link: String? = null,
)

internal data class WeiboPollOptionSnapshot(
    val id: String? = null,
    val text: String,
    val votes: Long? = null,
    val displayVotes: String? = null,
)

internal enum class WeiboPollStatus {
    OPEN,
    CLOSED,
    UNKNOWN,
}

internal data class WeiboTimelinePage(
    val posts: List<WeiboPostSnapshot> = emptyList(),
    val nextCursor: String? = null,
)

internal data class WeiboFollowGroupSnapshot(
    val id: String,
    val name: String,
    val mode: String? = null,
    val memberCount: Long? = null,
    val exists: Boolean = false,
)

internal interface WeiboGateway {
    fun exportCookie(): String = ""

    suspend fun checkLoginState(): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "不支持微博登录状态检查",
        )
    }

    suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot?

    suspend fun fetchUserTimeline(
        userId: String,
        sinceEpochSeconds: Long? = null,
    ): WeiboTimelinePage

    suspend fun fetchFollowTimeline(
        sinceEpochSeconds: Long? = null,
    ): WeiboTimelinePage {
        return WeiboTimelinePage()
    }

    suspend fun enrichPost(post: WeiboPostSnapshot): WeiboPostSnapshot {
        return post
    }

    suspend fun fetchPostDetail(postId: String): WeiboPostSnapshot? {
        throw UnsupportedOperationException("不支持微博详情查询")
    }

    suspend fun fetchAssignedFollowGroups(userId: String): List<WeiboFollowGroupSnapshot> {
        return emptyList()
    }

    suspend fun fetchAvailableFollowGroups(userId: String): List<WeiboFollowGroupSnapshot> {
        return emptyList()
    }

    suspend fun createFollowGroup(name: String, isPublic: Boolean = false): WeiboFollowGroupSnapshot? {
        return null
    }

    suspend fun setFollowGroups(
        userId: String,
        listIds: List<String>,
        originListIds: List<String>,
    ): Boolean {
        return false
    }

    suspend fun queryFollowState(userId: String): FollowState {
        return FollowState.UNSUPPORTED
    }

    suspend fun followPublisher(userId: String): FollowActionResult {
        return FollowActionResult(
            status = FollowActionStatus.UNSUPPORTED,
            message = "不支持微博关注",
        )
    }

    suspend fun unfollowPublisher(userId: String): FollowActionResult {
        return FollowActionResult(
            status = FollowActionStatus.UNSUPPORTED,
            message = "不支持取消微博关注",
        )
    }
}

internal class UnsupportedWeiboGateway : WeiboGateway {
    override suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot? = null

    override suspend fun fetchUserTimeline(
        userId: String,
        sinceEpochSeconds: Long?,
    ): WeiboTimelinePage = WeiboTimelinePage()

    override suspend fun fetchPostDetail(postId: String): WeiboPostSnapshot? = null
}
