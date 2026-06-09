package top.colter.dynamic.weibo

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.plugin.SourceStateStore

internal const val WEIBO_DYNAMIC_FEED_KEY: String = "weibo.dynamic.feed"

internal interface WeiboCursorStore {
    fun get(publisherId: Int): SourceCursor?

    fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor

    fun markSeen(publisherId: Int, postId: String, timestamp: Long): SourceCursor

    fun evict(publisherId: Int)
}

internal class SourceStateWeiboCursorStore(
    private val stateStore: SourceStateStore,
) : WeiboCursorStore {
    private val cache: MutableMap<Int, SourceCursor> = ConcurrentHashMap()

    override fun get(publisherId: Int): SourceCursor? {
        cache[publisherId]?.let { return it }
        return stateStore
            .findCursor(
                publisherId = publisherId,
                sourceKey = WEIBO_DYNAMIC_FEED_KEY,
                eventType = SourceEventType.DYNAMIC_CREATED,
            )
            ?.also { cache[publisherId] = it }
    }

    override fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor {
        val updated = stateStore.ensureCursorBaseline(
            publisherId = publisherId,
            sourceKey = WEIBO_DYNAMIC_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            timestamp = timestamp,
        )
        cache[publisherId] = updated
        return updated
    }

    override fun markSeen(publisherId: Int, postId: String, timestamp: Long): SourceCursor {
        val updated = stateStore.markCursorSeen(
            publisherId = publisherId,
            sourceKey = WEIBO_DYNAMIC_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            updateKey = postId,
            timestamp = timestamp,
        )
        cache[publisherId] = updated
        return updated
    }

    override fun evict(publisherId: Int) {
        cache.remove(publisherId)
    }
}
