package top.colter.dynamic.weibo

import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeiboSessionCoordinatorTest {
    private val healthy = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "ok", PublisherLoginAccount(userId = "123"))
    private val expired = PublisherLoginResult(PublisherLoginStatus.FAILED, "expired")

    @Test
    fun `daily renewal is independent of successful validations and persists across restart`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway()
            val first = fixture.coordinator(gateway)
            first.check(waitMillis = null)
            assertEquals(1, gateway.refreshes)
            fixture.time += 6 * WeiboSessionCoordinator.HOUR
            first.check(waitMillis = null)
            assertEquals(1, gateway.refreshes)
            first.stop()
            val second = fixture.coordinator(gateway)
            second.check(waitMillis = null)
            assertEquals(1, gateway.refreshes)
            fixture.time += 18 * WeiboSessionCoordinator.HOUR
            second.check(waitMillis = null)
            assertEquals(2, gateway.refreshes)
        } finally { fixture.close() }
    }

    @Test
    fun `no credentials never makes a request`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway(cookie = "[]")
            fixture.coordinator(gateway).check(waitMillis = null)
            assertEquals(0, gateway.checks)
            assertEquals(0, gateway.refreshes)
        } finally { fixture.close() }
    }

    @Test
    fun `network failure backs off five minutes thirty minutes then six hours including restart`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway().apply { onCheck = { throw java.io.IOException("ticket=secret") } }
            var coordinator = fixture.coordinator(gateway)
            assertFalse(coordinator.check(waitMillis = null).message.contains("secret"))
            assertFalse(coordinator.isConfirmedExpired())
            repeat(4) { coordinator.check(waitMillis = null, knownExpired = true) }
            assertEquals(1, gateway.checks)
            coordinator.stop()
            coordinator = fixture.coordinator(gateway)
            coordinator.check(waitMillis = null)
            // A restart needs one validation; persisted backoff still prohibits SSO retries.
            assertEquals(2, gateway.checks)
            assertEquals(0, gateway.refreshes)
            fixture.time += 5 * WeiboSessionCoordinator.MINUTE
            coordinator.check(waitMillis = null)
            assertEquals(3, gateway.checks)
            fixture.time += 29 * WeiboSessionCoordinator.MINUTE
            coordinator.check(waitMillis = null)
            assertEquals(3, gateway.checks)
            fixture.time += WeiboSessionCoordinator.MINUTE
            coordinator.check(waitMillis = null)
            assertEquals(4, gateway.checks)
        } finally { fixture.close() }
    }

    @Test
    fun `successful validation resets its backoff and allows a later failure notification`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway()
            val coordinator = fixture.coordinator(gateway)
            coordinator.replace(gateway, healthy, renewed = true)
            gateway.onCheck = { throw java.io.IOException("temporary") }
            coordinator.check(waitMillis = null)
            var state = fixture.store.state as WeiboSessionState
            assertEquals(5 * WeiboSessionCoordinator.MINUTE, state.nextValidationAtMillis - fixture.time)
            assertEquals(1, state.validationFailureCount)
            assertEquals(1, fixture.notifications.size)

            fixture.time += 5 * WeiboSessionCoordinator.MINUTE
            gateway.onCheck = { healthy }
            assertEquals(PublisherLoginStatus.SUCCESS, coordinator.check(waitMillis = null).status)
            state = fixture.store.state as WeiboSessionState
            assertEquals(0, state.validationFailureCount)
            assertEquals(0L, state.nextValidationAtMillis)
            assertEquals(0, gateway.refreshes)

            gateway.onCheck = { throw java.io.IOException("another failure") }
            coordinator.check(waitMillis = null)
            state = fixture.store.state as WeiboSessionState
            assertEquals(5 * WeiboSessionCoordinator.MINUTE, state.nextValidationAtMillis - fixture.time)
            assertEquals(1, state.validationFailureCount)
            assertEquals(2, fixture.notifications.size)
        } finally { fixture.close() }
    }

    @Test
    fun `validation recovery preserves SSO backoff across restart`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway().apply { onRefresh = { throw java.io.IOException("renewal unavailable") } }
            var coordinator = fixture.coordinator(gateway)
            coordinator.check(waitMillis = null)
            fixture.time += 5 * WeiboSessionCoordinator.MINUTE
            coordinator.check(waitMillis = null)
            val renewalState = fixture.store.state as WeiboSessionState
            assertEquals(2, renewalState.failureCount)
            assertEquals(30 * WeiboSessionCoordinator.MINUTE, renewalState.nextAttemptAtMillis - fixture.time)

            gateway.onCheck = { throw java.io.IOException("validation unavailable") }
            coordinator.check(waitMillis = null)
            assertEquals(1, (fixture.store.state as WeiboSessionState).validationFailureCount)
            coordinator.stop()
            coordinator = fixture.coordinator(gateway)
            coordinator.check(waitMillis = null)
            assertEquals(1, (fixture.store.state as WeiboSessionState).validationFailureCount)

            fixture.time += 5 * WeiboSessionCoordinator.MINUTE
            gateway.onCheck = { healthy }
            assertEquals(PublisherLoginStatus.SUCCESS, coordinator.check(waitMillis = null).status)
            var state = fixture.store.state as WeiboSessionState
            assertEquals(0, state.validationFailureCount)
            assertEquals(renewalState.failureCount, state.failureCount)
            assertEquals(renewalState.nextAttemptAtMillis, state.nextAttemptAtMillis)
            assertEquals(2, gateway.refreshes)
            assertEquals(2, fixture.notifications.size)

            fixture.time = renewalState.nextAttemptAtMillis
            coordinator.check(waitMillis = null)
            state = fixture.store.state as WeiboSessionState
            assertEquals(3, gateway.refreshes)
            assertEquals(6 * WeiboSessionCoordinator.HOUR, state.nextAttemptAtMillis - fixture.time)
            assertEquals(2, fixture.notifications.size)
        } finally { fixture.close() }
    }

    @Test
    fun `proactive failure keeps good session and blocked refresh needs new credentials`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway().apply { onRefresh = { throw WeiboSessionException(WeiboSessionFailure.CHALLENGE) } }
            var coordinator = fixture.coordinator(gateway)
            assertEquals(PublisherLoginStatus.SUCCESS, coordinator.check(waitMillis = null).status)
            assertFalse(coordinator.isConfirmedExpired())
            fixture.time += 2 * WeiboSessionCoordinator.DAY
            coordinator.check(waitMillis = null)
            assertEquals(1, gateway.refreshes)
            assertEquals(1, fixture.notifications.size)
            coordinator.stop()
            coordinator = fixture.coordinator(gateway)
            coordinator.check(waitMillis = null)
            assertEquals(1, gateway.refreshes)
            assertEquals(1, fixture.notifications.size)
            val replacement = Gateway(cookie = "ALC=new; SUB=new")
            coordinator.replace(replacement)
            coordinator.check(waitMillis = null)
            assertEquals(1, replacement.refreshes)
        } finally { fixture.close() }
    }

    @Test
    fun `concurrent admin timeout and cancellation do not cancel shared refresh`() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val gateway = Gateway().apply { onCheck = { entered.complete(Unit); release.await(); healthy } }
            val coordinator = fixture.coordinator(gateway)
            assertEquals(PublisherLoginStatus.PENDING, coordinator.check(waitMillis = 20).status)
            entered.await()
            coroutineScope {
                val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { coordinator.check(waitMillis = null) }
                cancelledWaiter.cancel()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { coordinator.check(waitMillis = null) }
                release.complete(Unit)
                assertEquals(PublisherLoginStatus.SUCCESS, withTimeout(1000) { waiter.await() }.status)
            }
            assertEquals(PublisherLoginStatus.SUCCESS, coordinator.check().status)
            assertEquals(1, gateway.checks)
            assertEquals(1, gateway.refreshes)
        } finally { fixture.close() }
    }

    @Test
    fun `old generation cannot commit over newly logged in gateway`() = runBlocking {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        try {
            val old = Gateway().apply { onCheck = { release.await(); healthy } }
            val coordinator = fixture.coordinator(old)
            assertEquals(PublisherLoginStatus.PENDING, coordinator.check(waitMillis = 20).status)
            val generation = coordinator.generation()
            val replacement = Gateway(cookie = "SUB=replacement")
            assertTrue(coordinator.replace(replacement, healthy, generation))
            release.complete(Unit)
            assertFalse(coordinator.replace(old, healthy, generation))
            assertTrue(coordinator.gateway === replacement)
            assertEquals("SUB=replacement", fixture.savedCookie)
        } finally { fixture.close() }
    }

    @Test
    fun `failed cookie save retries and does not persist metadata ahead of credentials`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.saveAvailable = false
            val coordinator = fixture.coordinator(Gateway())
            coordinator.check(waitMillis = null)
            assertEquals(null, fixture.store.state)
            fixture.saveAvailable = true
            assertTrue(coordinator.persist())
            assertTrue(fixture.store.state != null)
            assertEquals("ALC=long; SUB=valid", fixture.savedCookie)
            val writes = fixture.store.writes
            coordinator.persist()
            assertEquals(writes, fixture.store.writes)
        } finally { fixture.close() }
    }

    @Test
    fun `configuration changes preserve renewal clock and coordinator can restart`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway()
            val coordinator = fixture.coordinator(gateway)
            coordinator.check(waitMillis = null)
            fixture.time += WeiboSessionCoordinator.HOUR
            val replacement = Gateway()
            coordinator.reconfigure(create = { replacement }, updateConfig = {}, credentialsChanged = false)
            coordinator.check(waitMillis = null)
            assertEquals(0, replacement.refreshes)
            coordinator.stop()
            coordinator.start()
            coordinator.check(waitMillis = null)
            assertEquals(2, replacement.checks)
            assertEquals(0, replacement.refreshes)
        } finally { fixture.close() }
    }

    @Test
    fun `cached checks retry failed persistence without another upstream request`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.saveAvailable = false
            val gateway = Gateway()
            val coordinator = fixture.coordinator(gateway)
            coordinator.check(waitMillis = null)
            fixture.saveAvailable = true
            coordinator.check(waitMillis = null, minimumIntervalMillis = WeiboSessionCoordinator.HOUR)
            assertEquals(1, gateway.checks)
            assertTrue(fixture.store.state != null)
        } finally { fixture.close() }
    }

    @Test
    fun `blocked expired credentials do not repeat gateway requests`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway().apply {
                onCheck = { expired }
                onRefresh = { throw WeiboSessionException(WeiboSessionFailure.LOGIN_REQUIRED) }
            }
            val coordinator = fixture.coordinator(gateway)
            coordinator.check(waitMillis = null)
            repeat(3) { coordinator.check(waitMillis = null, knownExpired = true) }
            assertEquals(1, gateway.checks)
            assertEquals(1, gateway.refreshes)
            assertTrue(coordinator.isConfirmedExpired())
        } finally { fixture.close() }
    }

    @Test
    fun `API expiry during proactive backoff does not reuse cached healthy state`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway().apply { onRefresh = { throw java.io.IOException("temporary") } }
            val coordinator = fixture.coordinator(gateway)
            assertEquals(PublisherLoginStatus.SUCCESS, coordinator.check(waitMillis = null).status)
            assertEquals(PublisherLoginStatus.FAILED, coordinator.check(waitMillis = null, knownExpired = true).status)
            assertEquals(PublisherLoginStatus.FAILED, coordinator.check(waitMillis = null, minimumIntervalMillis = WeiboSessionCoordinator.HOUR).status)
            assertEquals(1, gateway.checks)
        } finally { fixture.close() }
    }

    @Test
    fun `importing a healthy cookie does not count as SSO renewal`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = Gateway()
            val coordinator = fixture.coordinator(gateway)
            coordinator.replace(gateway, healthy)
            coordinator.check(waitMillis = null, minimumIntervalMillis = WeiboSessionCoordinator.HOUR)
            assertEquals(1, gateway.refreshes)
            val qrGateway = Gateway(cookie = "ALC=qr; SUB=qr")
            coordinator.replace(qrGateway, healthy, renewed = true)
            coordinator.check(waitMillis = null)
            assertEquals(0, qrGateway.refreshes)
        } finally { fixture.close() }
    }

    @Test
    fun `replacing credentials discards persisted cooldown from another session`() = runBlocking {
        val fixture = Fixture()
        try {
            val broken = Gateway().apply { onCheck = { throw java.io.IOException("temporary") } }
            val previous = fixture.coordinator(broken)
            previous.check(waitMillis = null)
            previous.stop()
            val replacement = Gateway(cookie = "ALC=replaced; SUB=replaced")
            fixture.coordinator(replacement).check(waitMillis = null)
            assertEquals(1, replacement.refreshes)
        } finally { fixture.close() }
    }

    @Test
    fun `stopping invalidates a login that began before shutdown`() = runBlocking {
        val fixture = Fixture()
        try {
            val coordinator = fixture.coordinator(Gateway())
            val generation = coordinator.generation()
            coordinator.stop()
            assertFalse(coordinator.replace(Gateway(cookie = "SUB=late"), healthy, generation))
            assertEquals("ALC=long; SUB=valid", fixture.savedCookie)
        } finally { fixture.close() }
    }

    private inner class Gateway(private val cookie: String = "ALC=long; SUB=valid") : WeiboGateway {
        var checks = 0
        var refreshes = 0
        var onCheck: suspend () -> PublisherLoginResult = { healthy }
        var onRefresh: suspend () -> PublisherLoginResult = { healthy }
        override fun exportCookie(): String = cookie
        override suspend fun checkLoginState(expectedUserId: String?): PublisherLoginResult { checks++; return onCheck() }
        override suspend fun refreshLoginSession(expectedUserId: String?): PublisherLoginResult { refreshes++; return onRefresh() }
        override suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot? = null
    }

    private class MemoryStore : PluginDataStore {
        var state: Any? = null
        var writes = 0
        override val dataDir: Path = Path.of("unused-test-data")
        override fun <T : Any> loadOrCreate(name: String, clazz: KClass<T>, migrations: List<ConfigMigration>, defaultProvider: () -> T): T {
            @Suppress("UNCHECKED_CAST")
            return (state as? T) ?: defaultProvider()
        }
        override fun <T : Any> save(name: String, value: T) { state = value; writes++ }
        override fun <T : Any> reload(name: String, clazz: KClass<T>, migrations: List<ConfigMigration>): T = error("unused")
        override fun exists(name: String): Boolean = state != null
        override fun delete(name: String): Boolean { state = null; return true }
        override fun resolvePath(name: String): Path = dataDir.resolve(name)
    }

    private class Fixture {
        var time = 100 * WeiboSessionCoordinator.DAY
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val notifications = mutableListOf<SystemNotificationPublishRequest>()
        var saveAvailable = true
        var savedCookie = ""
        fun coordinator(gateway: WeiboGateway) = WeiboSessionCoordinator(
            gateway, scope, store,
            persistCookies = { if (saveAvailable) savedCookie = it; saveAvailable },
            notifications = SystemNotificationPublisher { notifications += it; SystemNotificationPublishResult.accepted() },
            onHealthy = {}, now = { time },
        )
        fun close() { scope.cancel() }
    }
}
