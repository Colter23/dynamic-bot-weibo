package top.colter.dynamic.weibo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.SubscriptionSubscriber
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WeiboPublisherRuntimeTest {
    @Test
    fun `startup login check failure does not pause polling or notify administrators`() = runBlocking {
        val gateway = RecordingWeiboGateway(
            loginResult = PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "Cookie 已失效",
            ),
        )
        val scheduler = ManualTaskScheduler()
        val notifications = mutableListOf<SystemNotificationPublishRequest>()
        val runtime = WeiboPublisherRuntime(
            loadConfig = {
                WeiboPublisherConfig(
                    pollingEnabled = true,
                    maxConsecutiveLoginFailures = 1,
                    cookie = "SUB=expired",
                )
            },
            gatewayFactory = { gateway },
            cursorStoreFactory = { InMemoryWeiboCursorStore() },
            taskScheduler = scheduler,
        )
        runtime.onLoad(
            testContext(
                updates = RecordingSourceUpdatePublisher(),
                subscriptions = FixedSubscriptionQueryService(emptyList()),
                notificationPublisher = SystemNotificationPublisher { request ->
                    notifications += request
                    SystemNotificationPublishResult.accepted()
                },
            )
        )

        runtime.onStart()
        repeat(3) {
            runtime.checkLoginState()
        }

        assertFalse(scheduler.isRunning("weibo-detect"))
        assertEquals(4, gateway.loginCheckCount)
        assertEquals(emptyList(), notifications)
    }

    @Test
    fun `startup replay uses friend timeline and persists runtime cookie`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val publisher = testPublisher(id = 1, userId = "10001")
        val cursorStore = InMemoryWeiboCursorStore(
            initial = mapOf(
                publisher.id to SourceCursor(
                    publisherId = publisher.id,
                    sourceKey = WEIBO_DYNAMIC_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "seen-new",
                    lastSeenAtEpochSeconds = now - 60,
                    recentUpdateKeys = listOf("seen-new"),
                )
            )
        )
        val gateway = RecordingWeiboGateway(
            posts = listOf(
                testPost("missed-old", publisher.externalId, now - 300),
                testPost("seen-new", publisher.externalId, now - 60),
            ),
            exportedCookie = "SUB=new; XSRF-TOKEN=fresh",
        )
        val updates = RecordingSourceUpdatePublisher()
        var savedConfig: WeiboPublisherConfig? = null
        val scheduler = ManualTaskScheduler()
        val runtime = WeiboPublisherRuntime(
            loadConfig = {
                WeiboPublisherConfig(
                    pollingEnabled = true,
                    replayWindowMinutes = 10,
                    cookie = "SUB=old; XSRF-TOKEN=old",
                )
            },
            gatewayFactory = { gateway },
            cursorStoreFactory = { cursorStore },
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = scheduler,
        )
        runtime.onLoad(testContext(updates, FixedSubscriptionQueryService(listOf(publisher))))

        runtime.onStart()

        assertEquals(emptyList(), updates.requests.map { it.update.key.externalId })
        assertTrue(scheduler.isRunning("weibo-detect"))

        scheduler.runOnce("weibo-detect")

        assertEquals(listOf("missed-old"), updates.requests.map { it.update.key.externalId })
        assertEquals(2, gateway.followTimelineSinceValues.size)
        assertNotNull(gateway.followTimelineSinceValues.first())
        assertTrue(gateway.followTimelineSinceValues.first()!! >= now - 10 * 60 - 2)
        assertTrue(gateway.followTimelineSinceValues.first()!! <= now - 10 * 60 + 2)
        assertEquals(null as Long?, gateway.followTimelineSinceValues.last())
        assertEquals("SUB=new; XSRF-TOKEN=fresh", savedConfig?.cookie)
        val cursor = cursorStore.get(publisher.id)
        assertNotNull(cursor)
        assertTrue(cursor.recentUpdateKeys.contains("missed-old"))
        assertEquals(now - 60, cursor.lastSeenAtEpochSeconds)
    }

    @Test
    fun `friend timeline enriches only subscribed publish candidates`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val publisher = testPublisher(id = 1, userId = "10001")
        val cursorStore = InMemoryWeiboCursorStore(
            initial = mapOf(
                publisher.id to SourceCursor(
                    publisherId = publisher.id,
                    sourceKey = WEIBO_DYNAMIC_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "seen",
                    lastSeenAtEpochSeconds = now - 600,
                    recentUpdateKeys = listOf("seen"),
                )
            )
        )
        val gateway = RecordingWeiboGateway(
            posts = listOf(
                testPost("candidate", publisher.externalId, now - 300, isLongText = true),
                testPost("other-user", "20002", now - 300, isLongText = true),
                testPost("seen", publisher.externalId, now - 200, isLongText = true),
            ),
        )
        val updates = RecordingSourceUpdatePublisher()
        val scheduler = ManualTaskScheduler()
        val runtime = WeiboPublisherRuntime(
            loadConfig = {
                WeiboPublisherConfig(
                    pollingEnabled = true,
                    replayWindowMinutes = 10,
                )
            },
            gatewayFactory = { gateway },
            cursorStoreFactory = { cursorStore },
            taskScheduler = scheduler,
        )
        runtime.onLoad(testContext(updates, FixedSubscriptionQueryService(listOf(publisher))))

        runtime.onStart()
        scheduler.runOnce("weibo-detect")

        assertEquals(listOf("candidate"), updates.requests.map { it.update.key.externalId })
        assertEquals(listOf("candidate"), gateway.enrichedPostIds)
        assertEquals(listOf<Long?>(null), gateway.followTimelineSinceValues.takeLast(1))
    }

    @Test
    fun `replay window zero warms up current follow timeline then continues detection`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val publisher = testPublisher(id = 1, userId = "10001")
        val cursorStore = InMemoryWeiboCursorStore(
            initial = mapOf(
                publisher.id to SourceCursor(
                    publisherId = publisher.id,
                    sourceKey = WEIBO_DYNAMIC_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "old-seen",
                    lastSeenAtEpochSeconds = now - 5_000,
                    recentUpdateKeys = listOf("old-seen"),
                )
            )
        )
        val gateway = RecordingWeiboGateway(
            followTimelinePages = listOf(
                WeiboTimelinePage(
                    posts = listOf(
                        testPost("warmup-newer", publisher.externalId, now - 3_600),
                        testPost("warmup-older", publisher.externalId, now - 3_700),
                    ),
                ),
                WeiboTimelinePage(
                    posts = listOf(
                        testPost("current-new", publisher.externalId, now - 60),
                    ),
                ),
            ),
        )
        val updates = RecordingSourceUpdatePublisher()
        val scheduler = ManualTaskScheduler()
        val runtime = WeiboPublisherRuntime(
            loadConfig = {
                WeiboPublisherConfig(
                    pollingEnabled = true,
                    replayWindowMinutes = 0,
                )
            },
            gatewayFactory = { gateway },
            cursorStoreFactory = { cursorStore },
            taskScheduler = scheduler,
        )
        runtime.onLoad(testContext(updates, FixedSubscriptionQueryService(listOf(publisher))))

        runtime.onStart()
        scheduler.runOnce("weibo-detect")

        assertEquals(listOf("current-new"), updates.requests.map { it.update.key.externalId })
        assertEquals(listOf<Long?>(null, null), gateway.followTimelineSinceValues)
        val warmed = cursorStore.get(publisher.id)
        assertNotNull(warmed)
        assertTrue(warmed.recentUpdateKeys.contains("warmup-older"))
        assertTrue(warmed.recentUpdateKeys.contains("warmup-newer"))
        assertTrue(warmed.recentUpdateKeys.contains("current-new"))
        assertEquals(now - 60, warmed.lastSeenAtEpochSeconds)
    }

    @Test
    fun `friend timeline ignores subscribed publishers not present in latest account feed`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val covered = testPublisher(id = 1, userId = "10001")
        val uncovered = testPublisher(id = 2, userId = "20002")
        val cursorStore = InMemoryWeiboCursorStore(
            initial = mapOf(
                covered.id to SourceCursor(
                    publisherId = covered.id,
                    sourceKey = WEIBO_DYNAMIC_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "covered-seen",
                    lastSeenAtEpochSeconds = now - 600,
                    recentUpdateKeys = listOf("covered-seen"),
                ),
                uncovered.id to SourceCursor(
                    publisherId = uncovered.id,
                    sourceKey = WEIBO_DYNAMIC_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "uncovered-seen",
                    lastSeenAtEpochSeconds = now - 600,
                    recentUpdateKeys = listOf("uncovered-seen"),
                ),
            )
        )
        val gateway = RecordingWeiboGateway(
            posts = listOf(testPost("covered-new", covered.externalId, now - 60)),
        )
        val updates = RecordingSourceUpdatePublisher()
        val scheduler = ManualTaskScheduler()
        val runtime = WeiboPublisherRuntime(
            loadConfig = {
                WeiboPublisherConfig(
                    pollingEnabled = true,
                    replayWindowMinutes = 0,
                )
            },
            gatewayFactory = { gateway },
            cursorStoreFactory = { cursorStore },
            taskScheduler = scheduler,
        )
        runtime.onLoad(testContext(updates, FixedSubscriptionQueryService(listOf(covered, uncovered))))

        runtime.onStart()
        scheduler.runOnce("weibo-detect")

        assertEquals(emptyList(), updates.requests.map { it.update.key.externalId })
        assertTrue(cursorStore.get(covered.id)?.recentUpdateKeys?.contains("covered-new") == true)
        assertTrue(cursorStore.get(uncovered.id)?.recentUpdateKeys?.contains("uncovered-seen") == true)
    }

    @Test
    fun `friend timeline initializes cursor for uncovered publisher before next appearance`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val publisher = testPublisher(id = 1, userId = "10001")
        val cursorStore = InMemoryWeiboCursorStore()
        val gateway = RecordingWeiboGateway(
            followTimelinePages = listOf(
                WeiboTimelinePage(),
                WeiboTimelinePage(posts = listOf(testPost("other-user", "99999", now - 60))),
                WeiboTimelinePage(posts = listOf(testPost("new-post", publisher.externalId, now + 60))),
            ),
        )
        val updates = RecordingSourceUpdatePublisher()
        val scheduler = ManualTaskScheduler()
        val runtime = WeiboPublisherRuntime(
            loadConfig = {
                WeiboPublisherConfig(
                    pollingEnabled = true,
                    replayWindowMinutes = 0,
                )
            },
            gatewayFactory = { gateway },
            cursorStoreFactory = { cursorStore },
            taskScheduler = scheduler,
        )
        runtime.onLoad(testContext(updates, FixedSubscriptionQueryService(listOf(publisher))))

        runtime.onStart()
        scheduler.runOnce("weibo-detect")

        assertEquals(emptyList(), updates.requests.map { it.update.key.externalId })
        assertTrue(cursorStore.contains(publisher.id))

        scheduler.runOnce("weibo-detect")

        assertEquals(listOf("new-post"), updates.requests.map { it.update.key.externalId })
        assertTrue(cursorStore.get(publisher.id)?.recentUpdateKeys?.contains("new-post") == true)
    }

    @Test
    fun `subscription update without dynamic event evicts cursor`() = runBlocking {
        val publisher = testPublisher(id = 1, userId = "10001")
        val cursorStore = InMemoryWeiboCursorStore(
            initial = mapOf(
                publisher.id to SourceCursor(
                    publisherId = publisher.id,
                    sourceKey = WEIBO_DYNAMIC_FEED_KEY,
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    lastSeenUpdateKey = "seen",
                    lastSeenAtEpochSeconds = 100,
                    recentUpdateKeys = listOf("seen"),
                )
            )
        )
        val subscriber = testSubscriber()
        val enabledSubscription = testSubscription(publisher, subscriber)
        val disabledSubscription = enabledSubscription.copy(
            policy = SubscriptionPolicy(enabledEvents = emptySet()),
        )
        val subscriptions = object : SubscriptionQueryService {
            var snapshot: PublisherSubscribers = PublisherSubscribers(
                publisher = publisher,
                subscriptions = listOf(
                    SubscriptionSubscriber(
                        subscription = enabledSubscription,
                        subscriber = subscriber,
                    )
                ),
            )

            override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? {
                return snapshot.takeIf { it.publisher.id == publisherId }
            }

            override fun findActivePublishersWithSubscribersBySourcePlatform(
                platformId: String,
            ): List<PublisherSubscribers> {
                return listOf(snapshot).filter { it.publisher.platformId.value == platformId }
            }
        }
        val runtime = WeiboPublisherRuntime(
            loadConfig = { WeiboPublisherConfig(pollingEnabled = false) },
            gatewayFactory = { RecordingWeiboGateway() },
            cursorStoreFactory = { cursorStore },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext(RecordingSourceUpdatePublisher(), subscriptions))
        subscriptions.snapshot = PublisherSubscribers(
            publisher = publisher,
            subscriptions = listOf(
                SubscriptionSubscriber(
                    subscription = disabledSubscription,
                    subscriber = subscriber,
                )
            ),
        )

        runtime.onSubscriptionChanged(
            SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.UPDATED,
                subscription = disabledSubscription,
                publisher = publisher,
                subscriber = subscriber,
                changedAtEpochSeconds = disabledSubscription.updatedAtEpochSeconds,
            )
        )

        assertFalse(cursorStore.contains(publisher.id))
    }

    @Test
    fun `stop persists runtime cookie when changed`() = runBlocking {
        var savedConfig: WeiboPublisherConfig? = null
        val runtime = WeiboPublisherRuntime(
            loadConfig = {
                WeiboPublisherConfig(
                    pollingEnabled = false,
                    cookie = "SUB=old",
                )
            },
            gatewayFactory = {
                RecordingWeiboGateway(exportedCookie = "SUB=new; XSRF-TOKEN=fresh")
            },
            cursorStoreFactory = { InMemoryWeiboCursorStore() },
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext(RecordingSourceUpdatePublisher(), FixedSubscriptionQueryService(emptyList())))

        runtime.onStop()

        assertEquals("SUB=new; XSRF-TOKEN=fresh", savedConfig?.cookie)
    }

    @Test
    fun `publisher banner uses first semicolon separated cover url`() = runBlocking {
        val firstCover = "https://wx2.sinaimg.cn/crop.0.0.640.640.640/first.jpg"
        val secondCover = "https://wx3.sinaimg.cn/crop.0.0.640.640.640/second.jpg"
        val gateway = object : WeiboGateway {
            override suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot {
                return WeiboPublisherSnapshot(
                    userId = userId,
                    screenName = "测试用户",
                    avatarUrl = "https://example.com/avatar.png",
                    coverUrl = "$firstCover;$secondCover",
                )
            }
        }
        val runtime = WeiboPublisherRuntime(
            loadConfig = { WeiboPublisherConfig() },
            gatewayFactory = { gateway },
            cursorStoreFactory = { InMemoryWeiboCursorStore() },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext(RecordingSourceUpdatePublisher(), FixedSubscriptionQueryService(emptyList())))

        val info = runtime.fetchPublisherInfo("5977716744")

        assertNotNull(info)
        assertEquals(firstCover, info.banner?.uri)
    }

    private fun testContext(
        updates: SourceUpdatePublisher,
        subscriptions: SubscriptionQueryService,
        notificationPublisher: SystemNotificationPublisher = SystemNotificationPublisher {
            SystemNotificationPublishResult.accepted()
        },
    ): PluginContext {
        return PluginContext(
            pluginId = "weibo-publisher",
            descriptor = PluginDescriptor(
                id = "weibo-publisher",
                name = "微博动态源",
                version = "0.0.1",
                mainClass = "top.colter.dynamic.weibo.WeiboPublisherPlugin",
            ),
            configService = DummyConfigService,
            dataStore = DummyPluginDataStore,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            taskScheduler = ManualTaskScheduler(),
            sourceUpdatePublisher = updates,
            sourceStateStore = DummySourceStateStore,
            subscriptionQueryService = subscriptions,
            notificationPublisher = notificationPublisher,
        )
    }

    private fun testPublisher(id: Int, userId: String): Publisher {
        return Publisher(
            id = id,
            key = PublisherKey.of(WEIBO_PLATFORM_ID, PublisherKind.USER, userId),
            name = "微博用户 $userId",
            avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR),
            createTime = 1,
            createUser = 1,
        )
    }

    private fun testSubscriber(id: Int = 1): Subscriber {
        return Subscriber(
            id = id,
            address = TargetAddress.of("onebot", TargetKind.GROUP, "1000"),
            name = "测试群",
            state = EntityState.ACTIVE,
            createTime = 1,
            createUser = 1,
        )
    }

    private fun testSubscription(
        publisher: Publisher,
        subscriber: Subscriber,
        id: Int = 1,
        policy: SubscriptionPolicy = SubscriptionPolicy.default(),
    ): Subscription {
        return Subscription(
            id = id,
            subscriberId = subscriber.id,
            publisherId = publisher.id,
            createdAtEpochSeconds = 1,
            updatedAtEpochSeconds = 1,
            policy = policy,
        )
    }

    private fun testPost(
        id: String,
        userId: String,
        createdAt: Long,
        isLongText: Boolean = false,
    ): WeiboPostSnapshot {
        return WeiboPostSnapshot(
            postId = id,
            userId = userId,
            screenName = "微博用户 $userId",
            avatarUrl = "https://example.com/avatar.png",
            text = "微博 $id",
            rawText = "微博 $id",
            createdAtEpochSeconds = createdAt,
            url = postLink(userId, id),
            isLongText = isLongText,
        )
    }

    private class RecordingWeiboGateway(
        private val posts: List<WeiboPostSnapshot> = emptyList(),
        private val followTimelinePages: List<WeiboTimelinePage> = emptyList(),
        private val exportedCookie: String = "",
        private val loginResult: PublisherLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "登录成功"),
    ) : WeiboGateway {
        val followTimelineSinceValues: MutableList<Long?> = mutableListOf()
        val enrichedPostIds: MutableList<String> = mutableListOf()
        var loginCheckCount: Int = 0
            private set

        override fun exportCookie(): String = exportedCookie

        override suspend fun checkLoginState(): PublisherLoginResult {
            loginCheckCount += 1
            return loginResult
        }

        override suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot? = null

        override suspend fun fetchFollowTimeline(sinceEpochSeconds: Long?): WeiboTimelinePage {
            followTimelineSinceValues += sinceEpochSeconds
            return followTimelinePages.getOrNull(followTimelineSinceValues.lastIndex)
                ?: WeiboTimelinePage(posts = posts)
        }

        override suspend fun enrichPost(post: WeiboPostSnapshot): WeiboPostSnapshot {
            enrichedPostIds += post.postId
            return post.copy(
                text = "补全文本 ${post.postId}",
                rawText = "补全文本 ${post.postId}",
                isLongText = false,
            )
        }
    }

    private class InMemoryWeiboCursorStore(
        initial: Map<Int, SourceCursor> = emptyMap(),
    ) : WeiboCursorStore {
        private val cursors: MutableMap<Int, SourceCursor> = initial.toMutableMap()

        override fun get(publisherId: Int): SourceCursor? = cursors[publisherId]

        override fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor {
            return cursors[publisherId] ?: markSeen(publisherId, "__baseline__$timestamp", timestamp)
        }

        override fun markSeen(publisherId: Int, postId: String, timestamp: Long): SourceCursor {
            val recent = LinkedHashSet(cursors[publisherId]?.recentUpdateKeys.orEmpty())
            recent.add(postId)
            val cursor = SourceCursor(
                publisherId = publisherId,
                sourceKey = WEIBO_DYNAMIC_FEED_KEY,
                eventType = SourceEventType.DYNAMIC_CREATED,
                lastSeenUpdateKey = postId,
                lastSeenAtEpochSeconds = timestamp,
                recentUpdateKeys = recent.toList(),
            )
            cursors[publisherId] = cursor
            return cursor
        }

        override fun evict(publisherId: Int) {
            cursors.remove(publisherId)
        }

        fun contains(publisherId: Int): Boolean = publisherId in cursors
    }

    private class RecordingSourceUpdatePublisher : SourceUpdatePublisher {
        val requests: MutableList<SourceUpdatePublishRequest> = mutableListOf()

        override suspend fun publish(request: SourceUpdatePublishRequest): SourceUpdatePublishResult {
            requests += request
            return SourceUpdatePublishResult.enqueued(1)
        }
    }

    private class FixedSubscriptionQueryService(
        publishers: List<Publisher>,
    ) : SubscriptionQueryService {
        private val snapshots: List<PublisherSubscribers> = publishers.mapIndexed { index, publisher ->
            val subscriber = Subscriber(
                id = index + 1,
                address = TargetAddress.of("onebot", TargetKind.GROUP, "1000"),
                name = "测试群",
                state = EntityState.ACTIVE,
                createTime = 1,
                createUser = 1,
            )
            PublisherSubscribers(
                publisher = publisher,
                subscriptions = listOf(
                    SubscriptionSubscriber(
                        subscription = Subscription(
                            id = index + 1,
                            subscriberId = subscriber.id,
                            publisherId = publisher.id,
                            createdAtEpochSeconds = 1,
                            updatedAtEpochSeconds = 1,
                        ),
                        subscriber = subscriber,
                    )
                ),
            )
        }

        override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? {
            return snapshots.firstOrNull { it.publisher.id == publisherId }
        }

        override fun findActivePublishersWithSubscribersBySourcePlatform(
            platformId: String,
        ): List<PublisherSubscribers> {
            return snapshots.filter { it.publisher.platformId.value == platformId }
        }
    }

    private class ManualTaskScheduler : TaskScheduler {
        private val tasks: MutableMap<String, TaskDefinition> = linkedMapOf()
        private val running: MutableSet<String> = linkedSetOf()

        override fun start(task: TaskDefinition): Boolean {
            tasks[task.id] = task
            return running.add(task.id)
        }

        suspend fun runOnce(id: String) {
            require(id in running) { "任务未运行：$id" }
            val task = tasks[id] ?: error("任务不存在：$id")
            task.action()
        }

        override fun start(id: String): Boolean {
            return if (id in tasks) running.add(id) else false
        }

        override suspend fun stop(id: String): Boolean = running.remove(id)

        override suspend fun restart(id: String): Boolean {
            stop(id)
            return start(id)
        }

        override suspend fun stopAll() {
            running.clear()
        }

        override suspend fun shutdown() {
            running.clear()
            tasks.clear()
        }

        override fun isRunning(id: String): Boolean = id in running

        override fun snapshot(id: String): TaskSnapshot? = null

        override fun snapshots(): List<TaskSnapshot> = emptyList()
    }

    private object DummyConfigService : ConfigService {
        override fun <T : Any> loadOrCreate(
            pluginId: String,
            clazz: KClass<T>,
            migrations: List<ConfigMigration>,
            defaultProvider: () -> T,
        ): T = defaultProvider()

        override fun <T : Any> save(pluginId: String, config: T) = Unit

        override fun <T : Any> reload(
            pluginId: String,
            clazz: KClass<T>,
            migrations: List<ConfigMigration>,
        ): T = error("未配置测试配置：$pluginId")

        override fun exists(pluginId: String): Boolean = false

        override fun delete(pluginId: String): Boolean = false

        override fun resolvePath(pluginId: String): Path = createTempDirectory("weibo-config").resolve("$pluginId.yml")
    }

    private object DummyPluginDataStore : PluginDataStore {
        override val dataDir: Path = createTempDirectory("weibo-data")

        override fun <T : Any> loadOrCreate(
            name: String,
            clazz: KClass<T>,
            migrations: List<ConfigMigration>,
            defaultProvider: () -> T,
        ): T = defaultProvider()

        override fun <T : Any> save(name: String, value: T) = Unit

        override fun <T : Any> reload(
            name: String,
            clazz: KClass<T>,
            migrations: List<ConfigMigration>,
        ): T = error("未配置测试数据：$name")

        override fun exists(name: String): Boolean = false

        override fun delete(name: String): Boolean = false

        override fun resolvePath(name: String): Path = dataDir.resolve("$name.yml")
    }

    private object DummySourceStateStore : SourceStateStore {
        override fun findCursor(
            publisherId: Int,
            sourceKey: String,
            eventType: SourceEventType,
        ): SourceCursor? = null

        override fun ensureCursorBaseline(
            publisherId: Int,
            sourceKey: String,
            eventType: SourceEventType,
            timestamp: Long,
        ): SourceCursor = SourceCursor(publisherId, sourceKey, eventType, "__baseline__$timestamp", timestamp)

        override fun markCursorSeen(
            publisherId: Int,
            sourceKey: String,
            eventType: SourceEventType,
            updateKey: String,
            timestamp: Long,
        ): SourceCursor = SourceCursor(publisherId, sourceKey, eventType, updateKey, timestamp, listOf(updateKey))

        override fun findLatestLiveStatus(publisherId: Int): PublisherLiveStatus? = null

        override fun saveLiveStatus(state: PublisherLiveStatus): PublisherLiveStatus = state
    }
}
