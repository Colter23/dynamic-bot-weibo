package top.colter.dynamic.weibo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.hasSeen
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

internal const val WEIBO_PLATFORM_ID: String = "weibo"

private const val DEFAULT_PLUGIN_ID: String = "weibo-publisher"
private const val WEIBO_HOME: String = "https://weibo.com"
private const val DEFAULT_WEIBO_AVATAR: String = "https://weibo.com/favicon.ico"

private val logger = loggerFor<WeiboPublisherRuntime>()

internal class WeiboPublisherRuntime() :
    PublisherSourcePlugin,
    PublisherLookupPlugin,
    PublisherFollowPlugin,
    PublisherLoginProvider,
    LinkResolver,
    ConfigurablePlugin<WeiboPublisherConfig> {

    private var pluginId: String = DEFAULT_PLUGIN_ID
    private val detectTaskId: String = "weibo-detect"

    override val platformId: PlatformId = PlatformId.of(WEIBO_PLATFORM_ID)

    override val configId: String
        get() = pluginId
    override val configName: String = "微博动态源"
    override val configDescription: String = "微博动态轮询与请求配置。"
    override val configClass = WeiboPublisherConfig::class
    override val configFormSpec = WeiboPublisherConfigForm.spec

    private var loadConfig: (String) -> WeiboPublisherConfig = { id ->
        error("微博插件配置服务尚未初始化：$id")
    }
    private var saveConfig: (String, WeiboPublisherConfig) -> Unit = { _, _ -> }
    private var gatewayFactory: (WeiboPublisherConfig) -> WeiboGateway = { config ->
        WeiboHttpGateway(
            client = WeiboClient(config),
            requestIntervalMs = secondsToMillis(config.requestIntervalSeconds, minimumMillis = 0),
            followGroupName = config.followGroupName,
            autoCreateFollowGroup = config.autoCreateFollowGroup,
        )
    }
    private var cursorStoreFactory: () -> WeiboCursorStore = {
        error("微博游标存储尚未初始化")
    }

    private var useContextConfigService: Boolean = true
    private var useContextTaskScheduler: Boolean = true
    private var useContextStateStore: Boolean = true

    private lateinit var taskScheduler: TaskScheduler
    private lateinit var sourceUpdatePublisher: SourceUpdatePublisher
    private lateinit var notificationPublisher: SystemNotificationPublisher
    private lateinit var subscriptionQueryService: SubscriptionQueryService
    private lateinit var config: WeiboPublisherConfig
    private lateinit var gateway: WeiboGateway
    private lateinit var mapper: WeiboDynamicMapper
    private lateinit var linkResolver: WeiboLinkResolver
    private lateinit var requestFailureHandler: WeiboRequestFailureHandler
    private lateinit var cursorStore: WeiboCursorStore
    private lateinit var detectTask: TaskDefinition

    private val detectMutex: Mutex = Mutex()
    private val publisherLock: Any = Any()
    private val startupBootstrapLock: Any = Any()

    @Volatile
    private var activePublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var pendingDetection: Boolean = false

    private var startupReplayPending: Boolean = false
    private var startupCursorWarmupPending: Boolean = false

    internal constructor(
        loadConfig: (String) -> WeiboPublisherConfig,
        gatewayFactory: (WeiboPublisherConfig) -> WeiboGateway,
        cursorStoreFactory: () -> WeiboCursorStore,
        saveConfig: (String, WeiboPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
    ) : this() {
        this.loadConfig = loadConfig
        this.gatewayFactory = gatewayFactory
        this.cursorStoreFactory = cursorStoreFactory
        this.saveConfig = saveConfig
        this.taskScheduler = taskScheduler
        useContextConfigService = false
        useContextTaskScheduler = false
        useContextStateStore = false
    }

    override val supportedLoginMethods: Set<PublisherLoginMethod> = setOf(PublisherLoginMethod.COOKIE)
    override val supportsCookieExport: Boolean = true

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        sourceUpdatePublisher = context.sourceUpdatePublisher
        notificationPublisher = context.notificationPublisher
        subscriptionQueryService = context.subscriptionQueryService
        if (useContextTaskScheduler) {
            taskScheduler = context.taskScheduler
        }
        if (useContextStateStore) {
            cursorStoreFactory = { SourceStateWeiboCursorStore(context.sourceStateStore) }
        }
        if (useContextConfigService) {
            loadConfig = { id -> context.configService.loadOrCreate(id) { WeiboPublisherConfig() } }
            saveConfig = { id, next -> context.configService.save(id, next) }
        }

        config = loadConfig(pluginId)
        WeiboPublisherConfigForm.validate(config)
        gateway = gatewayFactory(config)
        mapper = WeiboDynamicMapper()
        requestFailureHandler = WeiboRequestFailureHandler(
            configProvider = { config },
            notificationPublisher = notificationPublisher,
        )
        linkResolver = WeiboLinkResolver(
            platformId = platformId,
            gatewayProvider = { gateway },
            mapper = mapper,
            publisherInfoResolver = ::fetchPublisherInfo,
            requestFailureHandler = requestFailureHandler,
        )
        cursorStore = cursorStoreFactory()
        detectTask = TaskDefinition(
            id = detectTaskId,
            name = "微博动态检测",
            description = "按配置间隔检测已订阅微博用户的新动态，并发布到主项目。",
            schedule = TaskSchedule.FixedDelay(config.pollingIntervalSeconds.seconds, runImmediately = true),
            action = { detectAndPublish() },
        )
        loadActivePublishers()
        logger.info { "微博插件已加载：pluginId=$pluginId，轮询启用=${config.pollingEnabled}" }
    }

    override suspend fun onStart() {
        if (!config.pollingEnabled) {
            logger.info { "微博轮询未启用；可在配置中开启后按间隔检测订阅动态" }
            return
        }
        val loginResult = checkLoginState()
        if (loginResult.status != PublisherLoginStatus.SUCCESS) {
            logger.warn {
                "微博轮询未启动：登录状态=${loginResult.status}，原因=${loginResult.message}"
            }
            return
        }
        requestFailureHandler.recordSuccess("微博启动登录状态检查")
        val taskStarted = bootstrapLoggedInState(allowReplay = true)
        logger.info {
            "微博轮询已就绪：账号=${loginResult.account?.name ?: loginResult.account?.userId ?: "未知"}，任务新启动=$taskStarted"
        }
    }

    override suspend fun onStop() {
        if (::taskScheduler.isInitialized) {
            taskScheduler.stop(detectTaskId)
        }
        runCatching { persistRuntimeCookieIfChanged() }
            .onFailure {
                logger.warn(it) { "停止微博轮询前回存 Cookie 失败" }
            }
        logger.info { "微博轮询已停止" }
    }

    override suspend fun onUnload() {
        runCatching { persistRuntimeCookieIfChanged() }
            .onFailure {
                logger.warn(it) { "卸载微博插件前回存 Cookie 失败" }
            }
    }

    override fun currentConfig(): WeiboPublisherConfig {
        return if (::config.isInitialized) config else loadConfig(pluginId)
    }

    override fun applyConfig(next: WeiboPublisherConfig): ConfigApplyResult {
        WeiboPublisherConfigForm.validate(next)
        val previous = currentConfig()
        if (previous == next) {
            return ConfigApplyResult(changed = false, message = "微博配置未变化")
        }

        config = next
        if (::gateway.isInitialized) {
            gateway = gatewayFactory(next)
        }

        val restartRequired = previous.pollingEnabled != next.pollingEnabled ||
            previous.pollingIntervalSeconds != next.pollingIntervalSeconds ||
            previous.requestIntervalSeconds != next.requestIntervalSeconds ||
            previous.cookie != next.cookie

        return ConfigApplyResult(
            changed = true,
            restartRequired = restartRequired,
            restartTargets = if (restartRequired) listOf("微博插件") else emptyList(),
            message = if (restartRequired) {
                "微博配置已保存；需要重启微博插件以重建轮询服务"
            } else {
                "微博配置已保存并生效"
            },
        )
    }

    override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
        val normalized = normalizeUserId(userId) ?: return null
        val snapshot = runWeiboRequest("发布者资料查询 uid=$normalized") {
            gateway.fetchPublisherSnapshot(normalized)
        }.getOrNull() ?: return null
        return snapshot.toPublisherInfo()
    }

    override suspend fun queryFollowState(userId: String): FollowState {
        val normalized = normalizeUserId(userId) ?: return FollowState.NOT_FOLLOWING
        return runWeiboRequest("关注状态查询 uid=$normalized") {
            gateway.queryFollowState(normalized)
        }.getOrDefault(FollowState.UNSUPPORTED)
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        val normalized = normalizeUserId(userId) ?: return FollowActionResult(
            status = top.colter.dynamic.core.plugin.FollowActionStatus.FAILED,
            message = "微博 UID 不能为空",
        )
        return runWeiboRequest("关注微博用户 uid=$normalized") {
            gateway.followPublisher(normalized)
        }.getOrElse {
            FollowActionResult(
                status = top.colter.dynamic.core.plugin.FollowActionStatus.FAILED,
                message = it.message ?: "微博关注失败",
            )
        }
    }

    override suspend fun unfollowPublisher(userId: String): FollowActionResult {
        val normalized = normalizeUserId(userId) ?: return FollowActionResult(
            status = top.colter.dynamic.core.plugin.FollowActionStatus.FAILED,
            message = "微博 UID 不能为空",
        )
        return runWeiboRequest("取消关注微博用户 uid=$normalized") {
            gateway.unfollowPublisher(normalized)
        }.getOrElse {
            FollowActionResult(
                status = top.colter.dynamic.core.plugin.FollowActionStatus.FAILED,
                message = it.message ?: "微博取消关注失败",
            )
        }
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        val result = try {
            gateway.checkLoginState()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "微博登录状态检查失败",
            )
        }
        if (result.status == PublisherLoginStatus.SUCCESS) {
            persistRuntimeCookieIfChanged()
        }
        return result
    }

    override fun matchesLink(inputUrl: String): Boolean {
        return linkResolver.matchesLink(inputUrl)
    }

    override suspend fun parseLink(inputUrl: String): ParsedLink? {
        return linkResolver.parseLink(inputUrl)
    }

    override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
        return linkResolver.resolveLink(parsedLink)
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        val trimmed = cookie.trim()
        if (trimmed.isBlank()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "微博 Cookie 不能为空")
        }
        val previous = currentConfig()
        val next = previous.copy(cookie = trimmed)
        config = next
        gateway = gatewayFactory(next)
        val result = checkLoginState()
        if (result.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("Cookie 登录")
            if (!persistRuntimeCookieIfChanged()) {
                saveConfig(pluginId, config)
            }
            if (config.pollingEnabled && ::taskScheduler.isInitialized) {
                bootstrapLoggedInState(allowReplay = !taskScheduler.isRunning(detectTaskId))
            }
        } else {
            config = previous
            gateway = gatewayFactory(previous)
        }
        return result
    }

    override suspend fun exportCookie(): String? {
        return currentConfig().cookie.trim().takeIf { it.isNotBlank() }
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        if (event.publisher.platformId != platformId) return

        when (event.changeType) {
            SubscriptionChangeType.SUBSCRIBED -> handleSubscribed(event)
            SubscriptionChangeType.UPDATED -> handleSubscribed(event)
            SubscriptionChangeType.UNSUBSCRIBED -> handleUnsubscribed(event)
        }
    }

    private suspend fun detectAndPublish() {
        if (!config.pollingEnabled) return
        if (::requestFailureHandler.isInitialized && requestFailureHandler.isPollingPaused()) {
            logger.debug { "微博检测跳过：登录状态失效，轮询请求已暂停" }
            return
        }
        if (!detectMutex.tryLock()) {
            pendingDetection = true
            logger.debug { "微博检测仍在执行，本轮已标记为补跑" }
            return
        }

        try {
            do {
                pendingDetection = false
                detectAndPublishLocked()
            } while (pendingDetection)
            persistRuntimeCookieIfChanged()
        } finally {
            detectMutex.unlock()
        }
    }

    private suspend fun detectAndPublishLocked() {
        loadActivePublishers(logSummary = false)
        runStartupBootstrapIfPending()
        val publisherSnapshot = activePublishers
        if (publisherSnapshot.isEmpty()) {
            logger.debug { "微博检测跳过：没有活跃订阅发布者" }
            return
        }

        val now = System.currentTimeMillis() / 1000
        detectByFriendTimeline(publisherSnapshot, now)
    }

    private suspend fun detectByFriendTimeline(
        publisherSnapshot: Map<Int, Publisher>,
        now: Long,
    ) {
        val page = runWeiboRequest("微博关注流拉取") {
            gateway.fetchFollowTimeline(null)
        }.getOrNull() ?: return

        val postsByUserId = page.posts
            .filter { it.postId.isNotBlank() && it.userId.isNotBlank() }
            .groupBy { it.userId }
        val publishersByExternalId = publisherSnapshot.values.groupBy { it.externalId }
        postsByUserId.forEach { (userId, posts) ->
            publishersByExternalId[userId].orEmpty().forEach { publisher ->
                detectPublisherPosts(
                    publisher = publisher,
                    rawPosts = posts,
                    now = now,
                    initializeCursorWhenEmpty = false,
                )
            }
        }
        initializeUncoveredPublisherCursors(
            publishers = publisherSnapshot.values,
            coveredUserIds = postsByUserId.keys,
            now = now,
        )
    }

    private fun initializeUncoveredPublisherCursors(
        publishers: Collection<Publisher>,
        coveredUserIds: Set<String>,
        now: Long,
    ) {
        var initializedCount = 0
        publishers.forEach { publisher ->
            if (publisher.externalId in coveredUserIds) return@forEach
            if (cursorStore.get(publisher.id) != null) return@forEach
            cursorStore.ensureBaseline(publisher.id, now)
            initializedCount += 1
        }
        if (initializedCount > 0) {
            logger.debug { "微博关注流未覆盖发布者已初始化游标：发布者=$initializedCount" }
        }
    }

    private suspend fun detectPublisherPosts(
        publisher: Publisher,
        rawPosts: List<WeiboPostSnapshot>,
        now: Long,
        initializeCursorWhenEmpty: Boolean = true,
    ) {
        val initialCursor = cursorStore.get(publisher.id)
        val posts = rawPosts
            .filter { it.postId.isNotBlank() }
            .sortedWith(compareBy<WeiboPostSnapshot> { it.createdAtEpochSeconds }.thenBy { it.postId })

        if (posts.isEmpty()) {
            if (initialCursor == null && initializeCursorWhenEmpty) {
                cursorStore.ensureBaseline(publisher.id, now)
            }
            return
        }

        if (initialCursor == null && config.replayWindowMinutes <= 0) {
            val latest = posts.maxWith(compareBy<WeiboPostSnapshot> { it.createdAtEpochSeconds }.thenBy { it.postId })
            cursorStore.markSeen(publisher.id, latest.postId, latest.createdAtEpochSeconds)
            logger.debug {
                "微博动态游标已初始化：publisherId=${publisher.id}，uid=${publisher.externalId}"
            }
            return
        }

        var cursor: SourceCursor = initialCursor
            ?: cursorStore.ensureBaseline(
                publisherId = publisher.id,
                timestamp = replayWindowStart(now),
            )

        for (post in posts) {
            if (post.createdAtEpochSeconds < cursor.lastSeenAtEpochSeconds || cursor.hasSeen(post.postId)) {
                continue
            }

            val enrichedPost = enrichPostForPublish(post)
            val update = mapper.map(enrichedPost, publisher) ?: continue
            logger.info {
                "微博检测到新动态：publisher=${publisher.displayLabel()}，uid=${publisher.externalId}，postId=${post.postId}"
            }
            if (publishSourceUpdate(update)) {
                cursor = cursorStore.markSeen(publisher.id, post.postId, post.createdAtEpochSeconds)
            } else {
                logger.warn {
                    "微博动态发布失败，已停止该发布者本轮后续处理，游标暂不越过失败动态：postId=${post.postId}"
                }
                return
            }
        }
    }

    private suspend fun bootstrapLoggedInState(allowReplay: Boolean): Boolean {
        loadActivePublishers()
        scheduleStartupBootstrap(allowReplay)
        return startDetectionTask()
    }

    private fun scheduleStartupBootstrap(allowReplay: Boolean) {
        synchronized(startupBootstrapLock) {
            if (config.replayWindowMinutes > 0 && allowReplay) {
                startupReplayPending = true
                startupCursorWarmupPending = false
            } else if (!taskScheduler.isRunning(detectTaskId)) {
                startupCursorWarmupPending = true
            }
        }
    }

    private fun takeStartupBootstrapPlan(): StartupBootstrapPlan {
        return synchronized(startupBootstrapLock) {
            StartupBootstrapPlan(
                replayMissingFollowTimeline = startupReplayPending,
                warmUpExistingCursors = startupCursorWarmupPending,
            ).also {
                startupReplayPending = false
                startupCursorWarmupPending = false
            }
        }
    }

    private suspend fun runStartupBootstrapIfPending(): Boolean {
        val plan = takeStartupBootstrapPlan()
        if (!plan.hasWork) return false

        if (plan.replayMissingFollowTimeline) {
            runCatching { replayMissingFollowTimeline() }
                .onFailure { error ->
                    logger.warn {
                        "微博关注流历史补发未完成：${error.message ?: "未知错误"}"
                    }
                    logger.debug(error) {
                        "微博关注流历史补发异常详情"
                    }
                }
        } else if (plan.warmUpExistingCursors) {
            runCatching { warmUpExistingCursors() }
                .onFailure { error ->
                    logger.warn(error) {
                        "微博动态游标预热失败"
                    }
                }
        }
        persistRuntimeCookieIfChanged()
        return true
    }

    private suspend fun warmUpExistingCursors() {
        val publisherSnapshot = activePublishers
        if (publisherSnapshot.isEmpty()) return

        val page = runWeiboRequest("微博动态游标预热") {
            gateway.fetchFollowTimeline(null)
        }.getOrNull() ?: return

        val postsByUserId = page.posts
            .filter { it.postId.isNotBlank() && it.userId.isNotBlank() }
            .groupBy { it.userId }
        if (postsByUserId.isEmpty()) return

        val publishersByExternalId = publisherSnapshot.values.groupBy { it.externalId }
        var warmedCount = 0
        postsByUserId.forEach { (userId, posts) ->
            publishersByExternalId[userId].orEmpty().forEach publisherLoop@{ publisher ->
                var cursor = cursorStore.get(publisher.id) ?: return@publisherLoop
                posts
                    .sortedWith(compareBy<WeiboPostSnapshot> { it.createdAtEpochSeconds }.thenBy { it.postId })
                    .forEach postLoop@{ post ->
                        if (post.createdAtEpochSeconds < cursor.lastSeenAtEpochSeconds || cursor.hasSeen(post.postId)) {
                            return@postLoop
                        }
                        cursor = cursorStore.markSeen(publisher.id, post.postId, post.createdAtEpochSeconds)
                        warmedCount += 1
                    }
            }
        }
        if (warmedCount > 0) {
            logger.info { "微博动态游标预热完成：动态=$warmedCount" }
        }
    }

    private suspend fun replayMissingFollowTimeline() {
        val publisherSnapshot = activePublishers
        if (publisherSnapshot.isEmpty()) return

        val now = System.currentTimeMillis() / 1000
        val lowerBound = replayWindowStart(now)
        val page = runWeiboRequest("微博关注流历史补发") {
            gateway.fetchFollowTimeline(lowerBound)
        }.getOrThrow()

        val postsByUserId = page.posts
            .filter { it.postId.isNotBlank() && it.userId.isNotBlank() && it.createdAtEpochSeconds >= lowerBound }
            .groupBy { it.userId }
        if (postsByUserId.isEmpty()) return

        val publishersByExternalId = publisherSnapshot.values.groupBy { it.externalId }
        var replayedCount = 0
        postsByUserId.forEach { (userId, posts) ->
            publishersByExternalId[userId].orEmpty().forEach publisherLoop@{ publisher ->
                var cursor = cursorStore.get(publisher.id)
                    ?: cursorStore.ensureBaseline(publisher.id, lowerBound)
                posts
                    .sortedWith(compareBy<WeiboPostSnapshot> { it.createdAtEpochSeconds }.thenBy { it.postId })
                    .forEach postLoop@{ post ->
                        if (post.createdAtEpochSeconds < lowerBound || cursor.hasSeen(post.postId)) {
                            return@postLoop
                        }
                        val enrichedPost = enrichPostForPublish(post)
                        val update = mapper.map(enrichedPost, publisher) ?: return@postLoop
                        logger.debug {
                            "微博补发历史动态：publisher=${publisher.displayLabel()}，uid=${publisher.externalId}，postId=${post.postId}"
                        }
                        if (publishSourceUpdate(update)) {
                            cursor = markSeenMonotonic(publisher.id, cursor, post)
                            replayedCount += 1
                        } else {
                            logger.warn {
                                "微博历史动态补发失败，已停止该发布者本轮补发，游标暂不越过失败动态：postId=${post.postId}"
                            }
                            return@publisherLoop
                        }
                    }
            }
        }
        if (replayedCount > 0) {
            logger.info {
                "微博关注流历史补发完成：发布者=${publisherSnapshot.size}，动态=$replayedCount"
            }
        }
    }

    private suspend fun handleSubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshot(publisherId)
            return
        }

        val becamePresent = applyPublisherSnapshot(snapshot)
        if (becamePresent && cursorStore.get(publisherId) == null) {
            cursorStore.ensureBaseline(publisherId, event.subscription.createdAtEpochSeconds)
        }
        if (config.pollingEnabled && taskScheduler.isRunning(detectTaskId)) {
            detectAndPublish()
        }
    }

    private fun handleUnsubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshot(publisherId)
            cursorStore.evict(publisherId)
        } else {
            applyPublisherSnapshot(snapshot)
        }
    }

    private fun applyPublisherSnapshot(snapshot: PublisherSubscribers): Boolean {
        val publisherId = snapshot.publisher.id
        val hasDynamic = snapshot.hasDynamicEventSubscription()
        val becamePresent = synchronized(publisherLock) {
            val wasPresent = activePublishers.containsKey(publisherId)
            activePublishers = if (hasDynamic) {
                activePublishers + (publisherId to snapshot.publisher)
            } else {
                activePublishers - publisherId
            }
            hasDynamic && !wasPresent
        }
        if (!hasDynamic) {
            cursorStore.evict(publisherId)
        }
        return becamePresent
    }

    private fun removePublisherFromSnapshot(publisherId: Int) {
        synchronized(publisherLock) {
            activePublishers = activePublishers - publisherId
        }
    }

    private fun loadActivePublishers(logSummary: Boolean = true) {
        val loaded = subscriptionQueryService
            .findActivePublishersWithSubscribersBySourcePlatform(platformId.value)
            .filter { it.hasDynamicEventSubscription() }
            .map { it.publisher }
            .associateBy { it.id }
        synchronized(publisherLock) {
            activePublishers = loaded
        }
        if (logSummary) {
            logger.info { "微博订阅发布者已加载：动态=${loaded.size}" }
        }
    }

    private suspend fun <T> runWeiboRequest(
        operation: String,
        block: suspend () -> T,
    ): Result<T> {
        return requestFailureHandler.run(operation, block)
    }

    private suspend fun publishSourceUpdate(update: SourceUpdate): Boolean {
        logger.debug {
            "微博提交来源更新到主项目：event=${update.eventType.value}，update=${update.key.stableValue()}，publisher=${update.publisher.displayLabel()}"
        }
        val result = sourceUpdatePublisher.publish(
            SourceUpdatePublishRequest(
                sourcePlugin = pluginId,
                update = update,
            )
        )
        if (result.accepted) {
            logger.debug {
                "微博来源更新已进入主项目：update=${update.key.stableValue()}，结果=${result.message}"
            }
        } else {
            logger.warn {
                "微博来源更新发布失败，游标暂不推进：update=${update.key.stableValue()}，原因=${result.message}"
            }
        }
        return result.accepted
    }

    private suspend fun enrichPostForPublish(post: WeiboPostSnapshot): WeiboPostSnapshot {
        return runWeiboRequest("微博长文补全 postId=${post.postId}") {
            gateway.enrichPost(post)
        }.getOrElse { error ->
            logger.warn {
                "微博长文补全失败，使用列表摘要继续处理：postId=${post.postId}，原因=${error.message ?: "未知错误"}"
            }
            post
        }
    }

    private fun startDetectionTask(): Boolean {
        val started = taskScheduler.start(detectTask)
        if (started) {
            logger.info { "微博检测任务已启动：taskId=$detectTaskId" }
        } else {
            logger.debug { "微博检测任务已在运行：taskId=$detectTaskId" }
        }
        return started
    }

    private fun markSeenMonotonic(
        publisherId: Int,
        cursor: SourceCursor,
        post: WeiboPostSnapshot,
    ): SourceCursor {
        return cursorStore.markSeen(
            publisherId = publisherId,
            postId = post.postId,
            timestamp = maxOf(cursor.lastSeenAtEpochSeconds, post.createdAtEpochSeconds),
        )
    }

    private fun persistRuntimeCookieIfChanged(): Boolean {
        if (!::gateway.isInitialized) return false
        val latest = gateway.exportCookie().trim().takeIf { it.isNotBlank() } ?: return false
        if (latest == config.cookie.trim()) return false
        config = config.copy(cookie = latest)
        runCatching {
            saveConfig(pluginId, config)
        }.onFailure {
            logger.warn(it) { "回存微博运行期 Cookie 失败" }
        }
        logger.debug { "微博运行期 Cookie 已回存配置" }
        return true
    }

    private fun replayWindowStart(now: Long): Long {
        return now - config.replayWindowMinutes.coerceAtLeast(0).toLong() * SECONDS_PER_MINUTE
    }

    private fun normalizeUserId(userId: String): String? {
        return userId.trim().takeIf { it.isNotBlank() }
    }

    private fun WeiboPublisherSnapshot.toPublisherInfo(): PublisherInfo {
        val normalizedUserId = normalizeUserId(userId) ?: userId
        return PublisherInfo(
            key = PublisherKey.of(
                platformId = WEIBO_PLATFORM_ID,
                kind = PublisherKind.USER,
                externalId = normalizedUserId,
            ),
            name = screenName.takeIf { it.isNotBlank() } ?: "微博用户 $normalizedUserId",
            avatar = MediaRef(
                uri = avatarUrl.takeIfNotBlank() ?: DEFAULT_WEIBO_AVATAR,
                kind = MediaKind.AVATAR,
            ),
            banner = coverUrl.takeFirstSemicolonSeparatedValue()?.let { MediaRef(it, MediaKind.COVER) },
        )
    }

    private fun PublisherSubscribers.hasDynamicEventSubscription(): Boolean {
        return subscriptions.any { item ->
            item.subscription.state == EntityState.ACTIVE &&
                item.subscriber.state == EntityState.ACTIVE &&
                SubscriptionEventKind.DYNAMIC in item.subscription.policy.enabledEvents
        }
    }

    private fun Publisher.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private fun PublisherInfo.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private fun String?.takeIfNotBlank(): String? {
        return this?.takeIf { it.isNotBlank() }
    }

    private fun String?.takeFirstSemicolonSeparatedValue(): String? {
        return this
            ?.splitToSequence(';')
            ?.map(String::trim)
            ?.firstOrNull(String::isNotBlank)
    }

    private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
        if (seconds <= 0.0 && minimumMillis <= 0) return 0
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
    }

    private data class StartupBootstrapPlan(
        val replayMissingFollowTimeline: Boolean,
        val warmUpExistingCursors: Boolean,
    ) {
        val hasWork: Boolean
            get() = replayMissingFollowTimeline || warmUpExistingCursors
    }

    private companion object {
        private const val SECONDS_PER_MINUTE: Long = 60L
    }
}

internal fun postLink(userId: String, postId: String): String {
    return "$WEIBO_HOME/$userId/$postId"
}
