package top.colter.dynamic.weibo

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeiboRequestFailureHandlerTest {
    @Test
    fun `pause polling after consecutive login failures and recover after success`() = runBlocking {
        val notifications = mutableListOf<SystemNotificationPublishRequest>()
        val handler = WeiboRequestFailureHandler(
            configProvider = { WeiboPublisherConfig(maxConsecutiveLoginFailures = 2) },
            notificationPublisher = SystemNotificationPublisher { request ->
                notifications += request
                SystemNotificationPublishResult.accepted()
            },
        )

        handler.run("微博关注流拉取") {
            throw WeiboLoginException("Cookie 已失效")
        }

        assertFalse(handler.isPollingPaused())

        handler.run("微博关注流拉取") {
            throw WeiboLoginException("Cookie 已失效")
        }

        assertTrue(handler.isPollingPaused())
        assertEquals("weibo.login_paused", notifications.single().type)

        handler.run("微博关注流拉取") {
            "ok"
        }

        assertFalse(handler.isPollingPaused())
        assertEquals("weibo.login_recovered", notifications.last().type)
    }
}
