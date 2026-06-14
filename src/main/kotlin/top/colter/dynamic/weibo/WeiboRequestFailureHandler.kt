package top.colter.dynamic.weibo

import kotlinx.coroutines.CancellationException
import top.colter.dynamic.core.event.NoopSystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.tools.loggerFor

private val requestFailureLogger = loggerFor<WeiboRequestFailureHandler>()

internal open class WeiboApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class WeiboLoginException(
    message: String,
    cause: Throwable? = null,
) : WeiboApiException(message, cause)

internal class WeiboRequestFailureHandler(
    private val configProvider: () -> WeiboPublisherConfig,
    private val notificationPublisher: SystemNotificationPublisher = NoopSystemNotificationPublisher,
) {
    private var consecutiveLoginFailures: Int = 0
    private var pollingPausedByLoginFailure: Boolean = false

    fun isPollingPaused(): Boolean = pollingPausedByLoginFailure

    suspend fun <T> run(
        operation: String,
        block: suspend () -> T,
    ): Result<T> {
        return try {
            val result = block()
            recordSuccess(operation)
            Result.success(result)
        } catch (error: Throwable) {
            recordFailure(operation, error)
            Result.failure(error)
        }
    }

    suspend fun recordSuccess(operation: String) {
        val wasPaused = pollingPausedByLoginFailure
        if (consecutiveLoginFailures > 0 || pollingPausedByLoginFailure) {
            requestFailureLogger.info {
                "微博请求已恢复：operation=$operation，之前连续未登录失败=$consecutiveLoginFailures"
            }
        }
        consecutiveLoginFailures = 0
        pollingPausedByLoginFailure = false
        if (wasPaused) {
            publishNotification(
                SystemNotificationPublishRequest(
                    type = "weibo.login_recovered",
                    severity = SystemNotificationSeverity.INFO,
                    title = "微博登录状态已恢复",
                    content = "微博请求已恢复，轮询可以继续执行。",
                    dedupeKey = "weibo.login_recovered",
                    details = mapOf("operation" to operation),
                ),
            )
        }
    }

    suspend fun recordFailure(operation: String, error: Throwable) {
        if (error is CancellationException) throw error

        when (error) {
            is WeiboLoginException -> recordLoginFailure(
                operation = operation,
                message = error.message ?: "微博登录状态不可用",
                cause = error,
            )
            is WeiboApiException -> recordApiFailure(operation, error)
            else -> recordUnknownFailure(operation, error)
        }
    }

    private suspend fun recordLoginFailure(
        operation: String,
        message: String,
        cause: Throwable?,
    ) {
        consecutiveLoginFailures += 1
        val threshold = configProvider().maxConsecutiveLoginFailures
        if (threshold > 0 && consecutiveLoginFailures >= threshold) {
            if (!pollingPausedByLoginFailure) {
                pollingPausedByLoginFailure = true
                if (cause == null) {
                    requestFailureLogger.error {
                        "微博登录状态失效，已暂停轮询请求：operation=$operation，连续未登录失败=$consecutiveLoginFailures，阈值=$threshold。请重新登录或更新 Cookie。"
                    }
                } else {
                    requestFailureLogger.error(cause) {
                        "微博登录状态失效，已暂停轮询请求：operation=$operation，连续未登录失败=$consecutiveLoginFailures，阈值=$threshold。请重新登录或更新 Cookie。"
                    }
                }
                publishNotification(
                    SystemNotificationPublishRequest(
                        type = "weibo.login_paused",
                        severity = SystemNotificationSeverity.ERROR,
                        title = "微博登录状态失效",
                        content = "微博连续请求未登录，已暂停轮询请求。请重新登录或更新 Cookie。",
                        dedupeKey = "weibo.login_paused",
                        details = mapOf(
                            "operation" to operation,
                            "consecutiveLoginFailures" to consecutiveLoginFailures.toString(),
                            "threshold" to threshold.toString(),
                            "error" to message,
                        ),
                    ),
                )
            } else {
                if (cause == null) {
                    requestFailureLogger.warn {
                        "微博轮询仍处于未登录暂停状态：operation=$operation，连续未登录失败=$consecutiveLoginFailures，阈值=$threshold"
                    }
                } else {
                    requestFailureLogger.warn(cause) {
                        "微博轮询仍处于未登录暂停状态：operation=$operation，连续未登录失败=$consecutiveLoginFailures，阈值=$threshold"
                    }
                }
            }
            return
        }

        val logMessage = if (threshold > 0) {
            "微博请求未登录：operation=$operation，连续未登录失败=$consecutiveLoginFailures/$threshold，原因=$message"
        } else {
            "微博请求未登录：operation=$operation，自动暂停已关闭，原因=$message"
        }
        if (cause == null) {
            requestFailureLogger.warn { logMessage }
        } else {
            requestFailureLogger.warn(cause) {
                logMessage
            }
        }
    }

    private fun recordApiFailure(operation: String, error: WeiboApiException) {
        consecutiveLoginFailures = 0
        requestFailureLogger.warn(error) {
            "微博接口请求失败：operation=$operation，原因=${error.message ?: "未知"}"
        }
    }

    private fun recordUnknownFailure(operation: String, error: Throwable) {
        consecutiveLoginFailures = 0
        requestFailureLogger.warn(error) {
            "微博请求出现未知异常：operation=$operation，类型=${error::class.qualifiedName ?: error::class.simpleName ?: "未知"}，原因=${error.message ?: "未知"}"
        }
    }

    private suspend fun publishNotification(request: SystemNotificationPublishRequest) {
        runCatching { notificationPublisher.publish(request) }
            .onFailure {
                requestFailureLogger.warn(it) { "微博系统通知发布失败：type=${request.type}" }
            }
    }
}
