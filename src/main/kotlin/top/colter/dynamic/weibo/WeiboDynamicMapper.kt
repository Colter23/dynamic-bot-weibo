package top.colter.dynamic.weibo

import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNode
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicContentTagType
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PollBlock
import top.colter.dynamic.core.data.PollOption
import top.colter.dynamic.core.data.PollStatus
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.UpdateKey
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class WeiboDynamicMapper {
    fun map(source: WeiboPostSnapshot, fallbackPublisher: Publisher): SourceUpdate? {
        return map(source, fallbackPublisher, depth = 0)
    }

    private fun map(source: WeiboPostSnapshot, fallbackPublisher: Publisher, depth: Int): SourceUpdate? {
        val postId = source.postId.takeIfNotBlank() ?: return null
        val publisher = buildPublisher(source, fallbackPublisher)
        val link = source.url.takeIfNotBlank() ?: postLink(publisher.externalId, postId)
        return SourceUpdate(
            key = UpdateKey(
                publisherKey = publisher.key,
                eventType = SourceEventType.DYNAMIC_CREATED,
                externalId = postId,
            ),
            publisher = publisher,
            occurredAtEpochSeconds = source.createdAtEpochSeconds,
            observedAtEpochSeconds = System.currentTimeMillis() / 1000,
            link = link,
            payload = DynamicPayload(
                labels = buildLabels(source),
                blocks = buildBlocks(source, fallbackPublisher, link, depth),
                metrics = buildMetrics(source.metrics),
            ),
        )
    }

    private fun buildPublisher(source: WeiboPostSnapshot, fallbackPublisher: Publisher): PublisherInfo {
        val fallback = fallbackPublisher.toInfo()
        val userId = source.userId.trim().takeIf { it.isNotBlank() } ?: fallback.externalId
        val unknownUser = userId == UNKNOWN_WEIBO_USER_ID
        val key = PublisherKey.of(
            platformId = WEIBO_PLATFORM_ID,
            kind = PublisherKind.USER,
            externalId = userId,
        )
        return fallback.copy(
            key = key,
            name = source.screenName.takeIfNotBlank()
                ?: UNKNOWN_WEIBO_USER_NAME.takeIf { unknownUser }
                ?: fallback.name,
            avatar = source.avatarUrl.toMediaRef(MediaKind.AVATAR)
                ?: if (unknownUser) MediaRef("", MediaKind.AVATAR) else fallback.avatar,
        )
    }

    private fun buildLabels(source: WeiboPostSnapshot): List<DynamicLabel> {
        return listOfNotNull(
            DynamicLabel("置顶", DynamicLabelKind.BADGE, "weibo.isTop").takeIf { source.isTop },
            source.badge.toLabel(DynamicLabelKind.BADGE, "weibo.title"),
            source.source.toLabel(DynamicLabelKind.NOTICE, "weibo.source"),
            source.regionName.toLabel(DynamicLabelKind.NOTICE, "weibo.region"),
        ).distinctBy { it.sourceKey to it.text }
    }

    private fun buildBlocks(
        source: WeiboPostSnapshot,
        fallbackPublisher: Publisher,
        link: String,
        depth: Int,
    ): List<top.colter.dynamic.core.data.DynamicBlock> {
        return buildList {
            buildContent(source)?.let { content ->
                add(
                    TextBlock(
                        content = content,
                        link = link,
                        sourceKind = "weibo.text",
                    )
                )
            }
            source.mediaCards().forEach { card ->
                card.toMediaCardBlock()?.let(::add)
            }
            source.poll?.toPollBlock()?.let(::add)
            source.pictures
                .mapNotNull { it.toImageItem() }
                .takeIf { it.isNotEmpty() }
                ?.let { images ->
                    add(
                        ImageGridBlock(
                            images = images,
                            link = link,
                            sourceKind = "weibo.pic",
                        )
                    )
                }
            if (depth < MAX_REPOST_DEPTH) {
                source.reposted
                    ?.let { map(it, fallbackPublisher, depth + 1) }
                    ?.let { repost ->
                        add(
                            RepostBlock(
                                referenceKind = DynamicReferenceKind.ORIGIN,
                                key = repost.key,
                                link = repost.link,
                                embedded = repost,
                            )
                        )
                    }
            }
        }
    }

    private fun buildContent(source: WeiboPostSnapshot): DynamicContent? {
        val richText = source.rawText.takeIfNotBlank()
        val fallbackText = source.text.takeIfNotBlank()
        val nodes = when {
            richText != null -> richText.toDynamicContentNodes()
            fallbackText != null -> fallbackText.scanPlainContentNodes()
            else -> emptyList()
        }
        return nodes.takeIf { it.isNotEmpty() }?.let { DynamicContent(it.mergeAdjacentTextNodes()) }
    }

    private fun WeiboMediaCardSnapshot.toMediaCardBlock(): MediaCardBlock? {
        val resolvedTitle = title.takeIfNotBlank() ?: return null
        val resolvedKind = when (kind) {
            WeiboMediaCardKind.VIDEO -> DynamicMediaCardKind.VIDEO
            WeiboMediaCardKind.ARTICLE -> DynamicMediaCardKind.ARTICLE
            WeiboMediaCardKind.LIVE -> DynamicMediaCardKind.LIVE
            WeiboMediaCardKind.PRODUCT -> DynamicMediaCardKind.PRODUCT
            WeiboMediaCardKind.LINK -> DynamicMediaCardKind.LINK
        }
        return MediaCardBlock(
            style = when (kind) {
                WeiboMediaCardKind.VIDEO -> MediaCardStyle.LARGE
                WeiboMediaCardKind.ARTICLE -> MediaCardStyle.LARGE
                WeiboMediaCardKind.LIVE -> MediaCardStyle.SMALL
                else -> MediaCardStyle.MINI
            },
            card = DynamicMediaCard(
                kind = resolvedKind,
                sourceKind = "weibo.page_info:${kind.name.lowercase()}",
                id = id,
                title = resolvedTitle,
                description = description,
                info = info,
                cover = coverUrl.toMediaRef(MediaKind.COVER),
                durationSeconds = durationSeconds,
                mediaUri = mediaUrl,
                link = url,
            ),
        )
    }

    private fun WeiboPollSnapshot.toPollBlock(): PollBlock? {
        val resolvedTitle = title.takeIfNotBlank() ?: return null
        return PollBlock(
            id = id,
            title = resolvedTitle,
            options = options.mapNotNull { it.toPollOption() },
            status = when (status) {
                WeiboPollStatus.OPEN -> PollStatus.OPEN
                WeiboPollStatus.CLOSED -> PollStatus.CLOSED
                WeiboPollStatus.UNKNOWN -> PollStatus.UNKNOWN
            },
            link = link,
            sourceKind = "weibo.page_info:poll",
        )
    }

    private fun WeiboPollOptionSnapshot.toPollOption(): PollOption? {
        val resolvedText = text.takeIfNotBlank() ?: return null
        return PollOption(
            id = id,
            text = resolvedText,
            votes = votes,
            displayVotes = displayVotes,
        )
    }

    private fun WeiboImageSnapshot.toImageItem(): ImageItem? {
        return ImageItem(
            image = url.toMediaRef(MediaKind.IMAGE) ?: return null,
            width = width,
            height = height,
            badge = badge,
            alt = alt,
        )
    }

    private fun buildMetrics(metrics: WeiboPostMetrics): List<DynamicMetric> {
        return listOfNotNull(
            metrics.reposts.toMetric("forward"),
            metrics.comments.toMetric("comment"),
            metrics.likes.toMetric("like"),
        )
    }

    private fun Long?.toMetric(key: String): DynamicMetric? {
        val value = this ?: return null
        return DynamicMetric(
            key = key,
            raw = value,
            display = value.toDisplayCount(),
        )
    }

    private fun String?.toMediaRef(kind: MediaKind): MediaRef? {
        val value = takeIfNotBlank() ?: return null
        return MediaRef(uri = value, kind = kind)
    }

    private fun String.toDynamicContentNodes(): List<DynamicContentNode> {
        val normalized = replace(Regex("(?i)<br\\s*/?>"), "\n")
        if (!normalized.contains('<')) {
            return normalized.htmlDecode().trim().scanPlainContentNodes()
        }

        return buildList {
            var cursor = 0
            for (match in HTML_ANCHOR_REGEX.findAll(normalized)) {
                if (match.range.first > cursor) {
                    addAll(normalized.substring(cursor, match.range.first).htmlFragmentToContentNodes())
                }
                val attrs = match.groupValues[1]
                val body = match.groupValues[2].anchorDisplayText()
                val href = attrs.htmlAttr("href")?.normalizeWeiboUrl()
                add(body.toLinkedContentNode(href))
                cursor = match.range.last + 1
            }
            if (cursor < normalized.length) {
                addAll(normalized.substring(cursor).htmlFragmentToContentNodes())
            }
        }.mapNotNull { node ->
            when (node) {
                is DynamicContentNodeText -> node.takeIf { it.text.isNotEmpty() }
                else -> node.takeIf { it.text.isNotBlank() }
            }
        }
    }

    private fun String.htmlFragmentToContentNodes(): List<DynamicContentNode> {
        return buildList {
            var cursor = 0
            for (match in HTML_IMAGE_REGEX.findAll(this@htmlFragmentToContentNodes)) {
                if (match.range.first > cursor) {
                    addAll(substring(cursor, match.range.first).htmlToPlainText().scanPlainContentNodes())
                }
                val attrs = match.groupValues[1]
                val text = attrs.htmlAttr("alt") ?: attrs.htmlAttr("title")
                if (!text.isNullOrBlank()) {
                    add(
                        DynamicContentNodeEmoji(
                            text = text.htmlDecode(),
                            image = attrs.htmlAttr("src")
                                ?.normalizeWeiboUrl()
                                ?.let { MediaRef(uri = it, kind = MediaKind.EMOJI, alt = text.htmlDecode()) },
                        )
                    )
                }
                cursor = match.range.last + 1
            }
            if (cursor < this@htmlFragmentToContentNodes.length) {
                addAll(substring(cursor).htmlToPlainText().scanPlainContentNodes())
            }
        }
    }

    private fun String.toLinkedContentNode(url: String?): DynamicContentNode {
        val displayText = trim()
        return when {
            displayText.isWeiboMentionText() -> DynamicContentNodeMention(
                text = displayText,
                url = url,
            )
            displayText.isWeiboTopicText() -> DynamicContentNodeTag(
                text = displayText,
                tagType = DynamicContentTagType.TOPIC,
                externalId = displayText.trim('#').takeIf(String::isNotBlank),
                url = url,
            )
            else -> DynamicContentNodeLink(
                text = displayText,
                url = url,
            )
        }
    }

    private fun String.scanPlainContentNodes(): List<DynamicContentNode> {
        if (isEmpty()) return emptyList()
        val nodes = mutableListOf<DynamicContentNode>()
        val text = this
        var cursor = 0
        while (cursor < text.length) {
            val next = nextPlainToken(text, cursor)
            if (next == null) {
                nodes += DynamicContentNodeText(text.substring(cursor))
                break
            }
            if (next.start > cursor) {
                nodes += DynamicContentNodeText(text.substring(cursor, next.start))
            }
            nodes += when (next.kind) {
                PlainTokenKind.MENTION -> DynamicContentNodeMention(
                    text = next.text,
                    url = mentionUrl(next.text),
                )
                PlainTokenKind.TOPIC -> DynamicContentNodeTag(
                    text = next.text,
                    tagType = DynamicContentTagType.TOPIC,
                    externalId = next.text.trim('#').takeIf(String::isNotBlank),
                    url = topicSearchUrl(next.text),
                )
                PlainTokenKind.URL -> DynamicContentNodeLink(
                    text = next.text,
                    url = next.text,
                )
            }
            cursor = next.end
        }
        return nodes
    }

    private fun nextPlainToken(text: String, start: Int): PlainToken? {
        return listOfNotNull(
            URL_REGEX.find(text, start)?.toPlainToken(PlainTokenKind.URL),
            TOPIC_REGEX.find(text, start)?.toPlainToken(PlainTokenKind.TOPIC),
            MENTION_REGEX.find(text, start)?.toPlainToken(PlainTokenKind.MENTION),
        ).minWithOrNull(compareBy<PlainToken> { it.start }.thenByDescending { it.end - it.start })
    }

    private fun MatchResult.toPlainToken(kind: PlainTokenKind): PlainToken {
        val raw = value.trimEndUrlPunctuation()
        return PlainToken(
            kind = kind,
            text = raw,
            start = range.first,
            end = range.first + raw.length,
        )
    }

    private fun List<DynamicContentNode>.mergeAdjacentTextNodes(): List<DynamicContentNode> {
        val merged = mutableListOf<DynamicContentNode>()
        forEach { node ->
            val previous = merged.lastOrNull()
            if (previous is DynamicContentNodeText && node is DynamicContentNodeText) {
                merged[merged.lastIndex] = DynamicContentNodeText(previous.text + node.text)
            } else {
                merged += node
            }
        }
        return merged
    }

    private fun String.htmlToPlainText(): String {
        return replace(Regex("<[^>]+>"), "")
            .htmlDecode()
    }

    private fun String.anchorDisplayText(): String {
        val plainText = htmlToPlainText()
        val imageText = firstImageText()?.trim()
        return imageText
            ?.takeIf { it.isWeiboTopicText() || it.isWeiboMentionText() }
            ?: plainText
    }

    private fun String.firstImageText(): String? {
        val attrs = HTML_IMAGE_REGEX.find(this)?.groupValues?.getOrNull(1) ?: return null
        return attrs.htmlAttr("alt") ?: attrs.htmlAttr("title")
    }

    private fun String.htmlAttr(name: String): String? {
        val pattern = Regex("""(?is)(?:^|\s)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        val match = pattern.find(this) ?: return null
        return match.groupValues
            .drop(1)
            .firstOrNull { it.isNotBlank() }
            ?.htmlDecode()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.htmlDecode(): String {
        return replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { match ->
                match.groupValues[1].toIntOrNull()?.let { codePoint ->
                    runCatching { String(Character.toChars(codePoint)) }.getOrNull()
                } ?: match.value
            }
    }

    private fun String.normalizeWeiboUrl(): String? {
        val value = htmlDecode().trim().takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "$WEIBO_HOME$value"
            else -> value
        }
    }

    private fun mentionUrl(text: String): String {
        return "$WEIBO_HOME/n/${text.removePrefix("@")}"
    }

    private fun topicSearchUrl(text: String): String {
        return "https://s.weibo.com/weibo?q=${text.urlEncode()}"
    }

    private fun String.isWeiboMentionText(): Boolean {
        return startsWith("@") && length > 1
    }

    private fun String.isWeiboTopicText(): Boolean {
        return length > 2 && startsWith("#") && endsWith("#")
    }

    private fun String.trimEndUrlPunctuation(): String {
        return trimEnd(
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

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8)
    }

    private fun Long.toDisplayCount(): String {
        return when {
            this >= 100_000_000L -> "%.1f亿".format(this / 100_000_000.0).trimTrailingZero()
            this >= 10_000L -> "%.1f万".format(this / 10_000.0).trimTrailingZero()
            else -> toString()
        }
    }

    private fun String.trimTrailingZero(): String {
        return replace(".0", "")
    }

    private fun WeiboPostSnapshot.mediaCards(): List<WeiboMediaCardSnapshot> {
        return (listOfNotNull(card) + additionalCards)
            .distinctBy { it.id ?: it.url ?: it.title }
    }

    private fun String?.toLabel(kind: DynamicLabelKind, sourceKey: String): DynamicLabel? {
        val text = takeIfNotBlank() ?: return null
        return DynamicLabel(text = text, kind = kind, sourceKey = sourceKey)
    }

    private fun String?.takeIfNotBlank(): String? {
        return this?.takeIf { it.isNotBlank() }
    }

    private companion object {
        private const val MAX_REPOST_DEPTH = 2
        private const val WEIBO_HOME = "https://weibo.com"
        private const val UNKNOWN_WEIBO_USER_ID = "__unknown__"
        private const val UNKNOWN_WEIBO_USER_NAME = "未知微博用户"
        private val HTML_ANCHOR_REGEX = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
        private val HTML_IMAGE_REGEX = Regex("(?is)<img\\b([^>]*)>")
        private val URL_REGEX = Regex("""https?://[^\s<>"']+""")
        private val TOPIC_REGEX = Regex("""#[^#\r\n]{1,80}#""")
        private val MENTION_REGEX = Regex("""(?<![\p{L}\p{N}_])@[\p{L}\p{N}_\-.·\u4e00-\u9fff]{1,40}""")
    }

    private data class PlainToken(
        val kind: PlainTokenKind,
        val text: String,
        val start: Int,
        val end: Int,
    )

    private enum class PlainTokenKind {
        MENTION,
        TOPIC,
        URL,
    }
}
