package top.colter.dynamic.weibo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.colter.dynamic.core.config.ConfigNumberKind

class WeiboPublisherConfigFormTest {
    @Test
    fun `form should group visible settings and hide cookie`() {
        val fields = WeiboPublisherConfigForm.spec.fields
        val sections = fields.groupBy { it.section }

        assertEquals(setOf("轮询与风控", "动态与关注"), sections.keys)
        assertEquals(
            listOf(
                "pollingEnabled",
                "pollingIntervalSeconds",
                "requestIntervalSeconds",
                "maxConsecutiveLoginFailures",
            ),
            sections.getValue("轮询与风控").map { it.path },
        )
        assertEquals(
            listOf("replayWindowMinutes", "followGroupName", "autoCreateFollowGroup"),
            sections.getValue("动态与关注").map { it.path },
        )
        assertFalse(fields.any { it.path == "cookie" })
    }

    @Test
    fun `form should keep restart and number constraints`() {
        val fields = WeiboPublisherConfigForm.spec.fields.associateBy { it.path }

        assertTrue(fields.getValue("pollingEnabled").restartRequired)
        assertTrue(fields.getValue("pollingIntervalSeconds").restartRequired)
        assertTrue(fields.getValue("requestIntervalSeconds").restartRequired)
        assertEquals(5L, fields.getValue("pollingIntervalSeconds").min)
        assertEquals(0L, fields.getValue("requestIntervalSeconds").min)
        assertEquals(ConfigNumberKind.INTEGER, fields.getValue("replayWindowMinutes").numberKind)
        assertEquals(ConfigNumberKind.INTEGER, fields.getValue("maxConsecutiveLoginFailures").numberKind)
    }

    @Test
    fun `validator should reject invalid values`() {
        WeiboPublisherConfigForm.validate(WeiboPublisherConfig())

        assertFailsWith<IllegalArgumentException> {
            WeiboPublisherConfigForm.validate(WeiboPublisherConfig(pollingIntervalSeconds = 4.0))
        }
        assertFailsWith<IllegalArgumentException> {
            WeiboPublisherConfigForm.validate(WeiboPublisherConfig(requestIntervalSeconds = -0.1))
        }
        assertFailsWith<IllegalArgumentException> {
            WeiboPublisherConfigForm.validate(WeiboPublisherConfig(replayWindowMinutes = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            WeiboPublisherConfigForm.validate(WeiboPublisherConfig(maxConsecutiveLoginFailures = -1))
        }
    }
}
