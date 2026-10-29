package top.colter.dynamic.weibo

import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.net.URI
import kotlinx.coroutines.CancellationException

internal class WeiboLinkResolver(
    private val platformId: PlatformId,
    private val gatewayProvider: () -> WeiboGateway,
    private val mapper: WeiboDynamicMapper,
    private val publisherInfoResolver: suspend (String) -> PublisherInfo?,
    private val requestFailureHandler: WeiboRequestFailureHandler? = null,
    private val sessionRenewer: (suspend () -> PublisherLoginResult)? = null,
) {
    private val gateway: WeiboGateway
        get() = gatewayProvider()

    @Volatile
    private var lastSessionRenewedAtMillis: Long = 0L

    @Volatile
    private var lastSessionRenewResult: PublisherLoginResult? = null

    fun matchesLink(inputUrl: String): Boolean {
        return parseDirectLink(inputUrl.trim().trimUrlPunctuation()) != null
    }

    suspend fun parseLink(inputUrl: String): ParsedLink? {
        return parseDirectLink(inputUrl.trim().trimUrlPunctuation())
    }

    suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
        if (parsedLink.platformId != platformId) {
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "不支持的平台：${parsedLink.platformId.value}",
            )
        }

        return when (parsedLink.kind) {
            LinkKinds.DYNAMIC -> resolveDynamic(parsedLink)
            LinkKinds.USER -> resolveUserPreview(parsedLink)
            else -> LinkResolution.Failed(parsedLink, "不支持的微博链接类型：${parsedLink.kind}")
        }
    }

    private suspend fun resolveDynamic(parsedLink: ParsedLink): LinkResolution {
        val targetId = parsedLink.targetId
        val operation = "微博动态详情解析 id=$targetId"
        var attempt = fetchPostDetailResult(targetId)

        // 两种失败都可能是登录会话已失效：
        // 1) 接口返回 2xx 却解析不出动态字段（客户端只能返回 null）；
        // 2) 接口明确判定为未登录（WeiboLoginException）。
        // 此时先续期一次首页会话再重试，避免把“未登录”误报成“动态不存在”。
        val needsRenewal = if (attempt.isSuccess) {
            attempt.getOrNull() == null
        } else {
            attempt.exceptionOrNull() is WeiboLoginException
        }
        var renewal: SessionRenewal? = null
        if (needsRenewal) {
            renewal = renewSessionOnce()
            // 只有本次真的访问过首页（而不是命中冷却）才重试，避免“动态确实不存在”时多打一次详情请求。
            if (renewal != null && renewal.fresh && renewal.result.status == PublisherLoginStatus.SUCCESS) {
                attempt = fetchPostDetailResult(targetId)
            }
        }

        val detail = attempt.getOrNull()
        if (detail == null) {
            val failure = attempt.exceptionOrNull()
            val renewalState = renewal
            val reason = if (renewalState != null && renewalState.result.status != PublisherLoginStatus.SUCCESS) {
                // 会话续期也失败：这是登录问题，必须计入未登录失败，而不是当成“动态不存在”。
                // 请求本身抛异常时 runSilently 已经记过一次，这里不重复计数。
                if (failure == null) {
                    requestFailureHandler?.recordFailure(
                        operation = operation,
                        error = WeiboLoginException(renewalState.result.message),
                    )
                }
                loginExpiredReason(renewalState.result)
            } else if (failure != null) {
                resolveFailureReason(failure)
            } else {
                // 请求本身成功、只是没有这条动态；登录状态是好的。
                requestFailureHandler?.recordSuccess(operation)
                parseFailureReason(targetId)
            }
            return if (failure != null) {
                LinkResolution.Failed(parsedLink = parsedLink, reason = reason, cause = failure)
            } else {
                LinkResolution.Failed(parsedLink = parsedLink, reason = reason)
            }
        }

        requestFailureHandler?.recordSuccess(operation)

        detail.findDownloadableVideo()?.let { video ->
            return LinkResolution.Preview(
                parsedLink = parsedLink,
                preview = detail.toVideoPreview(video),
            )
        }

        val update = mapper.map(detail, fallbackPublisher())
            ?: return LinkResolution.Failed(parsedLink, "微博动态映射失败：${parsedLink.targetId}")

        return LinkResolution.Dynamic(parsedLink, update)
    }

    private suspend fun resolveUserPreview(parsedLink: ParsedLink): LinkResolution {
        val publisher = runCatching {
            publisherInfoResolver(parsedLink.targetId)
        }.getOrElse { error ->
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = error.message ?: "获取微博用户信息失败",
                cause = error,
            )
        } ?: return LinkResolution.Failed(parsedLink, "未找到微博用户：${parsedLink.targetId}")

        return LinkResolution.Preview(
            parsedLink = parsedLink,
            preview = LinkPreview(
                platformId = platformId,
                kind = LinkKinds.USER,
                id = publisher.externalId,
                url = userLink(publisher.externalId),
                title = publisher.name,
                description = "微博用户 ${publisher.externalId}",
                badge = "用户",
                cover = publisher.banner,
                publisher = publisher,
            ),
        )
    }

    private fun WeiboPostSnapshot.toVideoPreview(video: WeiboMediaCardSnapshot): LinkPreview {
        val userId = userId.takeIf { it.isNotBlank() }
        val publisher = userId?.let {
            PublisherInfo(
                key = PublisherKey.of(platformId.value, PublisherKind.USER, it),
                name = screenName?.takeIf { name -> name.isNotBlank() } ?: "微博用户 $it",
                avatar = MediaRef(avatarUrl?.takeIf { url -> url.isNotBlank() } ?: DEFAULT_WEIBO_AVATAR, MediaKind.AVATAR),
            )
        }
        return LinkPreview(
            platformId = platformId,
            kind = LinkKinds.VIDEO,
            id = postId,
            url = url?.takeIf { it.isNotBlank() } ?: dynamicLink(postId),
            title = video.title.takeIf { it.isNotBlank() }
                ?: text.takeIf { it.isNotBlank() }
                ?: "微博视频 $postId",
            description = video.description.takeIf { it.isNotBlank() } ?: text,
            badge = "视频",
            cover = video.coverUrl?.takeIf { it.isNotBlank() }?.let { MediaRef(it, MediaKind.COVER) },
            publisher = publisher,
            metrics = listOfNotNull(
                metrics.reposts?.let { DynamicMetric("repost", raw = it) },
                metrics.comments?.let { DynamicMetric("comment", raw = it) },
                metrics.likes?.let { DynamicMetric("like", raw = it) },
            ),
            durationSeconds = video.durationSeconds,
            publishedAtEpochSeconds = createdAtEpochSeconds,
        )
    }

    private fun parseDirectLink(inputUrl: String): ParsedLink? {
        if (inputUrl.isBlank()) return null
        val uri = runCatching { URI(inputUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase() ?: return null
        val pathSegments = uri.path
            ?.split("/")
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return when (host) {
            "weibo.com",
            "www.weibo.com" -> parseDesktopLink(pathSegments, inputUrl)
            "m.weibo.cn",
            "weibo.cn",
            "www.weibo.cn" -> parseMobileLink(pathSegments, inputUrl)
            else -> null
        }
    }

    private fun parseDesktopLink(
        pathSegments: List<String>,
        sourceUrl: String,
    ): ParsedLink? {
        return when {
            pathSegments.firstOrNull() == "detail" -> pathSegments.getOrNull(1)
                ?.takeIf { it.isWeiboPostId() }
                ?.let { dynamicParsedLink(it, sourceUrl) }
            pathSegments.firstOrNull() == "u" -> pathSegments.getOrNull(1)
                ?.takeIf { it.isWeiboUid() }
                ?.let { userParsedLink(it, sourceUrl) }
            pathSegments.size >= 2 && pathSegments[0].isWeiboUid() -> pathSegments[1]
                .takeIf { it.isWeiboPostId() }
                ?.let { dynamicParsedLink(it, sourceUrl) }
            pathSegments.size == 1 && pathSegments[0].isWeiboUid() -> userParsedLink(pathSegments[0], sourceUrl)
            else -> null
        }
    }

    private fun parseMobileLink(
        pathSegments: List<String>,
        sourceUrl: String,
    ): ParsedLink? {
        return when (pathSegments.firstOrNull()) {
            "detail",
            "status" -> pathSegments.getOrNull(1)
                ?.takeIf { it.isWeiboPostId() }
                ?.let { dynamicParsedLink(it, sourceUrl) }
            "u",
            "profile" -> pathSegments.getOrNull(1)
                ?.takeIf { it.isWeiboUid() }
                ?.let { userParsedLink(it, sourceUrl) }
            else -> null
        }
    }

    private fun dynamicParsedLink(postId: String, sourceUrl: String): ParsedLink {
        return ParsedLink(
            platformId = platformId,
            kind = LinkKinds.DYNAMIC,
            targetId = postId,
            normalizedUrl = dynamicLink(postId),
            sourceUrl = sourceUrl,
        )
    }

    private fun userParsedLink(userId: String, sourceUrl: String): ParsedLink {
        return ParsedLink(
            platformId = platformId,
            kind = LinkKinds.USER,
            targetId = userId,
            normalizedUrl = userLink(userId),
            sourceUrl = sourceUrl,
        )
    }

    private suspend fun fetchPostDetailResult(postId: String): Result<WeiboPostSnapshot?> {
        val handler = requestFailureHandler
        return if (handler != null) {
            // 用 runSilently：空结果不应当被计为“请求成功”，成功语义由 resolveDynamic 判定。
            handler.runSilently("微博动态详情解析 id=$postId") {
                gateway.fetchPostDetail(postId)
            }
        } else {
            runCatching { gateway.fetchPostDetail(postId) }
        }
    }

    /**
     * 解析失败时续期一次登录会话（访问微博首页，客户端会接收并更新 Set-Cookie）。
     * 冷却期内复用上一次结果，避免连续失败时反复请求首页；[SessionRenewal.fresh] 表示本次是否真的发起了续期。
     */
    private suspend fun renewSessionOnce(): SessionRenewal? {
        val renewer = sessionRenewer ?: return null
        val now = System.currentTimeMillis()
        lastSessionRenewResult?.let { cached ->
            if (now - lastSessionRenewedAtMillis < SESSION_RENEW_COOLDOWN_MILLIS) {
                return SessionRenewal(cached, fresh = false)
            }
        }
        val result = try {
            renewer()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "微博登录状态检查失败",
            )
        }
        lastSessionRenewedAtMillis = System.currentTimeMillis()
        lastSessionRenewResult = result
        return SessionRenewal(result, fresh = true)
    }

    private fun parseFailureReason(targetId: String): String {
        return "未找到微博动态：$targetId"
    }

    private fun resolveFailureReason(error: Throwable): String {
        if (error is WeiboLoginException) {
            return "微博登录状态不可用：${error.message ?: "请在后台刷新登录状态后重试"}"
        }
        return error.message ?: "获取微博详情失败"
    }

    private fun loginExpiredReason(renewal: PublisherLoginResult): String {
        return "微博登录状态已失效（${renewal.message}），请在后台「账号连接」页刷新登录状态或重新登录后再试"
    }

    private data class SessionRenewal(
        val result: PublisherLoginResult,
        val fresh: Boolean,
    )

    private fun fallbackPublisher(): Publisher {
        return Publisher(
            id = 0,
            key = PublisherKey.of(platformId.value, PublisherKind.USER, "unknown"),
            name = "",
            avatar = MediaRef(DEFAULT_WEIBO_AVATAR, MediaKind.AVATAR),
            createTime = 0,
            createUser = 0,
        )
    }

    private fun dynamicLink(postId: String): String {
        return "$WEIBO_HOME/detail/$postId"
    }

    private fun userLink(userId: String): String {
        return "$WEIBO_HOME/u/$userId"
    }

    private fun String.isWeiboUid(): Boolean {
        return isNotBlank() && all(Char::isDigit)
    }

    private fun String.isWeiboPostId(): Boolean {
        return isNotBlank() && all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun String.trimUrlPunctuation(): String {
        return trim().trimEnd(
            '.',
            ',',
            ';',
            ':',
            '!',
            '?',
            ')',
            ']',
            '}',
            '>',
            '。',
            '，',
            '；',
            '：',
            '！',
            '？',
            '）',
            '】',
            '》',
        )
    }

    private companion object {
        private const val WEIBO_HOME: String = "https://weibo.com"
        private const val DEFAULT_WEIBO_AVATAR: String = "https://weibo.com/favicon.ico"
        private const val SESSION_RENEW_COOLDOWN_MILLIS: Long = 5 * 60 * 1_000L
    }
}
