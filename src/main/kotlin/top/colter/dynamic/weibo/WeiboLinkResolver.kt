package top.colter.dynamic.weibo

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
import java.net.URI

internal class WeiboLinkResolver(
    private val platformId: PlatformId,
    private val gatewayProvider: () -> WeiboGateway,
    private val mapper: WeiboDynamicMapper,
    private val publisherInfoResolver: suspend (String) -> PublisherInfo?,
    private val requestFailureHandler: WeiboRequestFailureHandler? = null,
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
        val detailResult = requestFailureHandler?.run("微博动态详情解析 id=${parsedLink.targetId}") {
            gateway.fetchPostDetail(parsedLink.targetId)
        } ?: runCatching {
            gateway.fetchPostDetail(parsedLink.targetId)
        }
        val source = detailResult.getOrElse { error ->
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = error.message ?: "获取微博详情失败",
                cause = error,
            )
        } ?: return LinkResolution.Failed(parsedLink, "未找到微博动态：${parsedLink.targetId}")

        val update = mapper.map(source, fallbackPublisher())
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
