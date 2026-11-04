package top.colter.dynamic.weibo

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.tools.loggerFor

internal data class WeiboSessionState(
    val credentialDigest: String = "",
    val accountId: String? = null,
    val lastValidatedAtMillis: Long = 0,
    val lastRefreshAtMillis: Long = 0,
    val failureCount: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val blockedReason: String? = null,
    val lastFailureReason: String? = null,
    val validationFailureCount: Int = 0,
    val nextValidationAtMillis: Long = 0,
)

private val sessionLogger = loggerFor<WeiboSessionCoordinator>()

/** Owns refresh jobs so a short-lived admin HTTP request cannot cancel a shared refresh. */
internal class WeiboSessionCoordinator(
    initialGateway: WeiboGateway,
    parentScope: CoroutineScope,
    private val dataStore: PluginDataStore,
    private val persistCookies: (String) -> Boolean,
    private val notifications: SystemNotificationPublisher,
    private val onHealthy: suspend () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val parentContext = parentScope.coroutineContext
    private var job = SupervisorJob(parentContext[kotlinx.coroutines.Job])
    private var scope = CoroutineScope(parentContext + job)
    @Volatile var gateway: WeiboGateway = initialGateway
        private set
    private var version = 0L
    private var revision = 0L
    private var inFlight: Deferred<PublisherLoginResult>? = null
    private var lastResult: PublisherLoginResult? = null
    private var checkedAt = 0L
    private var confirmedExpired = false
    private var state = loadState(initialGateway.exportCookie())
    private var notifiedReason: String? = state.lastFailureReason
    private var notifiedValidationFailure = state.validationFailureCount > 0
    private var savedState: WeiboSessionState? = null
    private var pendingObserved = false

    suspend fun check(
        waitMillis: Long? = 4_000,
        minimumIntervalMillis: Long = 0,
        knownExpired: Boolean = false,
    ): PublisherLoginResult {
        val task = synchronized(lock) {
            if (!knownExpired && pendingObserved && inFlight?.isCompleted == true && lastResult != null) {
                pendingObserved = false
                persistLocked()
                return lastResult!!
            }
            if (gateway.exportCookie().let { it.isBlank() || it == "[]" }) {
                return PublisherLoginResult(PublisherLoginStatus.FAILED, "微博 Cookie 未配置")
            }
            if (knownExpired) {
                confirmedExpired = true
                lastResult = lastResult?.copy(status = PublisherLoginStatus.FAILED, message = "微博接口已报告未登录，等待会话恢复")
            }
            inFlight?.takeUnless { it.isCompleted } ?: run {
                val cached = lastResult
                val refreshDue = state.blockedReason == null && state.nextAttemptAtMillis <= now() &&
                    gateway.canRefreshSession() && now() - state.lastRefreshAtMillis >= DAY
                val retryAt = if (state.validationFailureCount > 0) state.nextValidationAtMillis else state.nextAttemptAtMillis
                val retryDue = cached?.status == PublisherLoginStatus.FAILED &&
                    (state.blockedReason == null || state.validationFailureCount > 0) && retryAt <= now()
                if (cached != null && !knownExpired && now() - checkedAt < minimumIntervalMillis && !refreshDue && !retryDue) {
                    persistLocked()
                    return cached
                }
                if (cached != null && knownExpired && state.blockedReason != null && cached.status != PublisherLoginStatus.SUCCESS) {
                    persistLocked()
                    return cached
                }
                if (cached != null && (state.blockedReason == null || state.validationFailureCount > 0) && retryAt > now() &&
                    (confirmedExpired || cached.status != PublisherLoginStatus.SUCCESS)) {
                    persistLocked()
                    return if (knownExpired) PublisherLoginResult(PublisherLoginStatus.FAILED, "微博会话恢复暂不可用，将在退避后重试") else cached
                }
                newCheck()
            }
        }
        if (waitMillis == null) return task.await()
        return withTimeoutOrNull(waitMillis) { task.await() } ?: run {
            synchronized(lock) { pendingObserved = true }
            PublisherLoginResult(PublisherLoginStatus.PENDING, "正在检查或续期微博登录会话，请稍后刷新")
        }
    }

    fun generation(): Long = synchronized(lock) { version }

    fun revision(): Long = synchronized(lock) { revision }

    private fun newCheck(): Deferred<PublisherLoginResult> {
        val currentVersion = version
        val currentGateway = gateway
        return scope.async(start = CoroutineStart.LAZY) { perform(currentVersion, currentGateway) }
            .also { inFlight = it; it.start() }
    }

    fun start() = synchronized(lock) {
        if (!job.isActive) {
            job = SupervisorJob(parentContext[kotlinx.coroutines.Job])
            scope = CoroutineScope(parentContext + job)
            inFlight = null
        }
    }

    fun reconfigure(create: (WeiboGateway) -> WeiboGateway, updateConfig: () -> Unit, credentialsChanged: Boolean) = synchronized(lock) {
        replace(create(gateway), preserveSession = !credentialsChanged, beforeReplace = updateConfig)
    }

    fun replace(
        next: WeiboGateway,
        login: PublisherLoginResult? = null,
        expectedVersion: Long? = null,
        preserveSession: Boolean = false,
        beforeReplace: () -> Unit = {},
        renewed: Boolean = false,
    ): Boolean = synchronized(lock) {
        if (expectedVersion != null && version != expectedVersion) return@synchronized false
        beforeReplace()
        version += 1
        revision += 1
        inFlight?.cancel()
        inFlight = null
        gateway = next
        state = if (preserveSession) state else WeiboSessionState(
            credentialDigest = digest(next.exportCookie()), accountId = login?.account?.userId,
            lastValidatedAtMillis = if (login?.status == PublisherLoginStatus.SUCCESS) now() else 0,
            lastRefreshAtMillis = if (renewed && login?.status == PublisherLoginStatus.SUCCESS) now() else 0,
        )
        if (!preserveSession) {
            lastResult = login
            checkedAt = if (login == null) 0 else now()
            confirmedExpired = false
            notifiedReason = null
            notifiedValidationFailure = false
        }
        pendingObserved = false
        persistLocked()
        true
    }

    fun persist(): Boolean = synchronized(lock) { persistLocked() }

    fun isConfirmedExpired(): Boolean = synchronized(lock) { confirmedExpired }

    suspend fun stop() {
        val stopped = synchronized(lock) {
            version += 1
            pendingObserved = false
            job
        }
        stopped.cancelAndJoin()
        persist()
    }

    private suspend fun perform(currentVersion: Long, target: WeiboGateway): PublisherLoginResult {
        val previousState = synchronized(lock) { state }
        var transientFailure = false
        var failure: WeiboSessionFailure? = null
        var result = try {
            target.checkLoginState(previousState.accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: WeiboSessionException) {
            failure = error.failure
            transientFailure = !error.failure.needsUser
            PublisherLoginResult(PublisherLoginStatus.FAILED, error.failure.description)
        } catch (_: Throwable) {
            transientFailure = true
            failure = WeiboSessionFailure.TRANSIENT
            PublisherLoginResult(PublisherLoginStatus.FAILED, WeiboSessionFailure.TRANSIENT.description)
        }
        var refreshed = false
        val wasExpired = synchronized(lock) {
            if (version != currentVersion) return PublisherLoginResult(PublisherLoginStatus.CANCELED, "微博登录凭据已更新")
            confirmedExpired
        }
        val sessionValid = result.status == PublisherLoginStatus.SUCCESS
        val refreshDue = now() - previousState.lastRefreshAtMillis >= DAY
        val canAttempt = previousState.blockedReason == null && previousState.nextAttemptAtMillis <= now()
        if (!transientFailure && failure?.needsUser != true && canAttempt && (refreshDue || !sessionValid || wasExpired)) {
            if (target.canRefreshSession()) {
                try {
                    val renewed = target.refreshLoginSession(previousState.accountId ?: result.account?.userId)
                    if (renewed.status == PublisherLoginStatus.SUCCESS) {
                        result = renewed
                        refreshed = true
                        failure = null
                    } else {
                        failure = WeiboSessionFailure.LOGIN_REQUIRED
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: WeiboSessionException) {
                    failure = error.failure
                } catch (_: Throwable) {
                    failure = WeiboSessionFailure.TRANSIENT
                }
            } else {
                failure = WeiboSessionFailure.MISSING_CREDENTIAL
            }
        }
        if (!canAttempt && !sessionValid && failure == null) {
            failure = previousState.blockedReason?.let { runCatching { WeiboSessionFailure.valueOf(it) }.getOrNull() }
        }
        if (failure != null) {
            result = if (sessionValid && !refreshed) {
                result.copy(message = "微博登录状态可用；自动续期未完成：${failure.description}")
            } else result.copy(message = failure.description)
        }
        if (result.status != PublisherLoginStatus.SUCCESS && failure == null) {
            failure = WeiboSessionFailure.LOGIN_REQUIRED
        }
        var notify: WeiboSessionFailure? = null
        val accepted = synchronized(lock) {
            if (version != currentVersion) false else {
                checkedAt = now()
                lastResult = result
                if (result.status == PublisherLoginStatus.SUCCESS) {
                    confirmedExpired = false
                    state = state.copy(
                        accountId = result.account?.userId ?: state.accountId, lastValidatedAtMillis = now(),
                        validationFailureCount = 0, nextValidationAtMillis = 0,
                    )
                    notifiedValidationFailure = false
                } else if (!transientFailure && failure != WeiboSessionFailure.TRANSIENT) {
                    confirmedExpired = true
                }
                if (transientFailure && previousState.nextValidationAtMillis <= now()) {
                    val count = (state.validationFailureCount + 1).coerceAtMost(3)
                    state = state.copy(validationFailureCount = count, nextValidationAtMillis = now() + retryDelay(count))
                    if (!notifiedValidationFailure) {
                        notify = WeiboSessionFailure.TRANSIENT
                        notifiedValidationFailure = true
                    }
                }
                if (refreshed) {
                    revision += 1
                    state = state.copy(lastRefreshAtMillis = now(), failureCount = 0, nextAttemptAtMillis = 0, blockedReason = null, lastFailureReason = null)
                    notifiedReason = null
                } else if (failure != null && !transientFailure && canAttempt) {
                    val count = (state.failureCount + 1).coerceAtMost(3)
                    state = state.copy(
                        failureCount = count,
                        nextAttemptAtMillis = now() + retryDelay(count),
                        blockedReason = failure.takeIf(WeiboSessionFailure::needsUser)?.name,
                        lastFailureReason = failure.name,
                    )
                    if (notifiedReason != failure.name) {
                        notify = failure
                        notifiedReason = failure.name
                    }
                }
                persistLocked()
                true
            }
        }
        if (accepted) {
            currentCoroutineContext().ensureActive()
            notify?.let { problem ->
                sessionLogger.warn { "微博自动续期未完成：type=${problem.name}" }
                try {
                    notifications.publish(SystemNotificationPublishRequest(
                        type = "weibo.session_refresh_failed", severity = SystemNotificationSeverity.WARN,
                        title = "微博自动续期未完成", content = problem.description,
                        dedupeKey = "weibo.session_refresh_failed:${problem.name}",
                    ))
                } catch (error: CancellationException) { throw error } catch (_: Throwable) {
                    sessionLogger.warn { "微博续期通知发送失败" }
                }
            }
            if (result.status == PublisherLoginStatus.SUCCESS) onHealthy()
        }
        return result
    }

    private fun persistLocked(): Boolean {
        val cookies = gateway.exportCookie()
        if (!persistCookies(cookies)) return false
        state = state.copy(credentialDigest = digest(cookies))
        if (state != savedState) {
            try {
                dataStore.save(STORE, state)
                savedState = state
            } catch (_: Throwable) {
                sessionLogger.warn { "微博会话元数据保存失败，将在后续检查时重试" }
                return false
            }
        }
        return true
    }

    private fun loadState(cookies: String): WeiboSessionState = try {
        val restored = if (dataStore.exists(STORE)) {
            dataStore.loadOrCreate(STORE, WeiboSessionState::class) { WeiboSessionState() }
        } else WeiboSessionState()
        restored.takeIf { it.credentialDigest == digest(cookies) } ?: WeiboSessionState(credentialDigest = digest(cookies))
    } catch (_: Throwable) {
        sessionLogger.warn { "微博会话元数据读取失败，将重新校验登录状态" }
        WeiboSessionState(credentialDigest = digest(cookies))
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        private const val STORE = "login-session"
        private fun retryDelay(count: Int): Long = listOf(5 * MINUTE, 30 * MINUTE, 6 * HOUR)[count - 1]
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
