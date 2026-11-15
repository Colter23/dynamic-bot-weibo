package top.colter.dynamic.weibo

import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import top.colter.dynamic.core.link.LinkVideoResource

internal class WeiboLinkResolver(
    private val platformId: PlatformId,
    private val gatewayProvider: () -> WeiboGateway,
    private val mapper: WeiboDynamicMapper,
    private val publisherInfoResolver: suspend (String) -> PublisherInfo?,
    private val requestFailureHandler: WeiboRequestFailureHandler? = null,
    private val sessionRenewer: (suspend (Boolean) -> PublisherLoginResult)? = null,
    private val isLoginExpired: () -> Boolean = { true },
    private val sessionRevision: () -> Long = { 0 },
) {
    private val gateway: WeiboGateway
        get() = gatewayProvider()

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

        val needsRenewal = if (attempt.isSuccess) {
            attempt.getOrNull() == null
        } else {
            attempt.exceptionOrNull() is WeiboLoginException
        }
        var renewal: SessionRenewal? = null
        if (needsRenewal) {
            val explicitlyExpired = attempt.exceptionOrNull() is WeiboLoginException
            val previousRevision = sessionRevision()
            renewal = renewSessionOnce(explicitlyExpired)
            if (renewal != null && renewal.result.status == PublisherLoginStatus.SUCCESS &&
                (explicitlyExpired || sessionRevision() != previousRevision)) {
                attempt = fetchPostDetailResult(targetId)
            }
        }

        val detail = attempt.getOrNull()
        if (detail == null) {
            val failure = attempt.exceptionOrNull()
            val renewalState = renewal
            val reason = if (renewalState != null && renewalState.result.status != PublisherLoginStatus.SUCCESS) {
                if (isLoginExpired()) {
                    requestFailureHandler?.recordFailure(
                        operation = operation,
                        error = WeiboLoginException(renewalState.result.message),
                    )
                }
                if (isLoginExpired()) loginExpiredReason(renewalState.result) else renewalState.result.message
            } else if (failure != null) {
                requestFailureHandler?.recordFailure(operation, failure)
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

        // 微博的帖子链接一律按“动态”返回：带视频的动态同样是动态，正文必须作为正文渲染。
        // 视频只是 payload 里的一个媒体卡块（MediaCardBlock/VIDEO），不需要也不应该降级成链接预览，
        // 否则预览渲染只有一张卡片，正文会被迫塞进卡片描述里。
        val update = mapper.map(detail, fallbackPublisher())
            ?: return LinkResolution.Failed(parsedLink, "微博动态映射失败：${parsedLink.targetId}")

        // 卡片照常渲染正文与视频卡；可下载资源只参与本次统一模板执行。
        val video = detail.findDownloadableVideo()?.let { card ->
            LinkVideoResource(platformId, parsedLink.targetId, card.durationSeconds, buildJsonObject {
                put("title", card.title)
                put("mediaUrl", card.mediaUrl.orEmpty())
                put("sources", buildJsonArray {
                    card.videoSources.forEach { source -> add(buildJsonObject {
                        put("quality", source.quality)
                        put("url", source.url)
                    }) }
                })
            })
        }
        return LinkResolution.Dynamic(parsedLink, update, video = video)
    }

    private suspend fun resolveUserPreview(parsedLink: ParsedLink): LinkResolution {
        val publisher = runCatching {
            publisherInfoResolver(parsedLink.targetId)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
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
        return try {
            Result.success(gateway.fetchPostDetail(postId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun renewSessionOnce(knownExpired: Boolean): SessionRenewal? {
        val renewer = sessionRenewer ?: return null
        val result = try {
            renewer(knownExpired)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "微博登录状态检查暂时失败，请稍后重试",
            )
        }
        return SessionRenewal(result)
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
    }
}
