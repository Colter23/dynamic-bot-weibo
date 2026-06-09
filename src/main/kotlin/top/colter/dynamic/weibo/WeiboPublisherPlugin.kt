package top.colter.dynamic.weibo

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.task.TaskScheduler

public class WeiboPublisherPlugin private constructor(
    private val runtime: WeiboPublisherRuntime,
) :
    PublisherSourcePlugin,
    PublisherLookupPlugin,
    PublisherFollowPlugin,
    PublisherLoginProvider,
    LinkResolver,
    ConfigurablePlugin<WeiboPublisherConfig> {

    public constructor() : this(WeiboPublisherRuntime())

    internal constructor(
        loadConfig: (String) -> WeiboPublisherConfig,
        gatewayFactory: (WeiboPublisherConfig) -> WeiboGateway,
        cursorStoreFactory: () -> WeiboCursorStore,
        saveConfig: (String, WeiboPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
    ) : this(
        WeiboPublisherRuntime(
            loadConfig = loadConfig,
            gatewayFactory = gatewayFactory,
            cursorStoreFactory = cursorStoreFactory,
            saveConfig = saveConfig,
            taskScheduler = taskScheduler,
        )
    )

    override val platformId: PlatformId
        get() = runtime.platformId

    override val configId: String
        get() = runtime.configId
    override val configName: String
        get() = runtime.configName
    override val configDescription: String
        get() = runtime.configDescription
    override val configClass = WeiboPublisherConfig::class
    override val configFormSpec = WeiboPublisherConfigForm.spec

    override val supportedLoginMethods: Set<PublisherLoginMethod>
        get() = runtime.supportedLoginMethods
    override val supportsCookieExport: Boolean
        get() = runtime.supportsCookieExport

    override suspend fun onLoad(context: PluginContext) {
        runtime.onLoad(context)
    }

    override suspend fun onStart() {
        runtime.onStart()
    }

    override suspend fun onStop() {
        runtime.onStop()
    }

    override fun currentConfig(): WeiboPublisherConfig {
        return runtime.currentConfig()
    }

    override fun applyConfig(next: WeiboPublisherConfig): ConfigApplyResult {
        return runtime.applyConfig(next)
    }

    override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
        return runtime.fetchPublisherInfo(userId)
    }

    override suspend fun queryFollowState(userId: String): FollowState {
        return runtime.queryFollowState(userId)
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        return runtime.followPublisher(userId)
    }

    override suspend fun unfollowPublisher(userId: String): FollowActionResult {
        return runtime.unfollowPublisher(userId)
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        return runtime.checkLoginState()
    }

    override fun matchesLink(inputUrl: String): Boolean {
        return runtime.matchesLink(inputUrl)
    }

    override suspend fun parseLink(inputUrl: String): ParsedLink? {
        return runtime.parseLink(inputUrl)
    }

    override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
        return runtime.resolveLink(parsedLink)
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        return runtime.loginByCookie(cookie)
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        return runtime.loginByQrCode(onQrCode, onStatusChanged)
    }

    override suspend fun exportCookie(): String? {
        return runtime.exportCookie()
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        runtime.onSubscriptionChanged(event)
    }
}
