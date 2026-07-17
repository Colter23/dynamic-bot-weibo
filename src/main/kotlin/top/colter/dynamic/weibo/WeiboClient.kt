package top.colter.dynamic.weibo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoDownloadResult
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.tools.loggerFor
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.Locale

private val logger = loggerFor<WeiboClient>()

internal data class WeiboQrCodeSnapshot(
    val id: String,
    val imageUrl: String,
)

internal data class WeiboQrLoginPollSnapshot(
    val code: Long,
    val message: String,
    val loginUrl: String? = null,
)

private data class WeiboSsoToken(
    val alt: String,
    val saveState: Int,
)

internal class WeiboClient(
    private val config: WeiboPublisherConfig,
    private val httpClient: HttpClient = defaultHttpClient(config.cookie),
) {
    private var cachedLoginAccount: PublisherLoginAccount? = null
    private val videoDownloader = WeiboVideoDownloader(httpClient, DESKTOP_USER_AGENT)

    suspend fun checkLoginState(): PublisherLoginResult {
        val hasCookie = currentCookieHeader().isNotBlank()
        if (!hasCookie) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "微博 Cookie 未配置",
            )
        }

        // 显式检查必须重新访问首页，不能使用关注流构造时缓存的账号信息。
        val account = fetchCurrentAccount(forceRefresh = true)
        if (account == null) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "微博登录状态不可用：PC 首页未包含当前账号信息",
            )
        }

        return PublisherLoginResult(
            status = PublisherLoginStatus.SUCCESS,
            message = "微博登录状态可用",
            account = account,
        )
    }

    suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit = { _ -> },
    ): PublisherLoginResult {
        return try {
            requestQrLoginPage()
            val qrCode = requestQrCode()
            val qrImageBytes = downloadQrCodeImage(qrCode.imageUrl)
            onQrCode(
                PublisherQrLoginChallenge(
                    qrImageBytes = qrImageBytes,
                    expiresAtEpochSeconds = System.currentTimeMillis() / 1_000 + QR_LOGIN_TIMEOUT_MILLIS / 1_000,
                    message = "请使用微博 App 扫码并确认登录",
                    instruction = "请使用微博 App 扫码并确认登录",
                    validityHint = "三分钟内有效",
                    statusPollIntervalMillis = QR_LOGIN_POLL_INTERVAL_MILLIS,
                ),
            )

            val deadlineAtMillis = System.currentTimeMillis() + QR_LOGIN_TIMEOUT_MILLIS
            var scanned = false
            while (System.currentTimeMillis() < deadlineAtMillis) {
                delay(QR_LOGIN_POLL_INTERVAL_MILLIS)
                val poll = requestQrLoginStatus(qrCode.id)
                when (poll.code) {
                    QR_LOGIN_WAITING_CODE -> Unit
                    QR_LOGIN_SCANNED_CODE -> {
                        if (!scanned) {
                            scanned = true
                            onStatusChanged(
                                PublisherLoginResult(
                                    status = PublisherLoginStatus.PENDING,
                                    message = "已扫码，等待手机确认登录",
                                ),
                            )
                        }
                    }
                    QR_LOGIN_SUCCESS_CODE -> {
                        onStatusChanged(
                            PublisherLoginResult(
                                status = PublisherLoginStatus.PENDING,
                                message = "扫码确认成功，正在获取登录会话",
                            ),
                        )
                        return completeQrLogin(poll)
                    }
                    QR_LOGIN_EXPIRED_CODE -> {
                        return PublisherLoginResult(
                            status = PublisherLoginStatus.EXPIRED,
                            message = poll.message.ifBlank { "微博二维码已过期" },
                        )
                    }
                    QR_LOGIN_CANCELED_CODE -> {
                        return PublisherLoginResult(
                            status = PublisherLoginStatus.CANCELED,
                            message = poll.message.ifBlank { "已取消微博二维码登录" },
                        )
                    }
                    else -> {
                        return PublisherLoginResult(
                            status = PublisherLoginStatus.FAILED,
                            message = poll.message.ifBlank { "微博二维码登录失败：code=${poll.code}" },
                        )
                    }
                }
            }
            PublisherLoginResult(PublisherLoginStatus.EXPIRED, "微博二维码已过期")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "微博二维码登录失败",
            )
        }
    }

    suspend fun refreshLoginSession(): PublisherLoginResult {
        if (!hasCookie("SRF")) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "微博会话恢复缺少 SRF Cookie",
            )
        }
        return try {
            val token = requestSsoRestoreToken()
                ?: return PublisherLoginResult(PublisherLoginStatus.FAILED, "微博会话恢复未获取到 SSO 令牌")
            val metaResponse = sendTextRequest(
                uri = buildExternalUri(
                    SSO_LOGIN_URI,
                    listOf(
                        "entry" to "sso",
                        "returntype" to "META",
                        "gateway" to "1",
                        "alt" to token.alt,
                        "savestate" to token.saveState.toString(),
                    ),
                ),
                referer = WEIBO_HOME,
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                xRequestedWith = false,
            )
            if (metaResponse.statusCode() !in 200..299) {
                return PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = "微博会话恢复 SSO 跳转失败：status=${metaResponse.statusCode()}",
                )
            }
            val crossDomainUri = parseSsoMetaRedirect(metaResponse.body())
                ?.let { value -> runCatching { URI.create(value) }.getOrNull() }
                ?.takeIf { it.host == LOGIN_SINA_HOST }
                ?: return PublisherLoginResult(PublisherLoginStatus.FAILED, "微博会话恢复未返回跨域跳转地址")
            val crossResponse = sendJsonpRequest(
                uri = buildExternalUri(
                    crossDomainUri.toString(),
                    listOf(
                        "action" to "login",
                        "entry" to "sso",
                        "r" to WEIBO_HOME,
                        "callback" to loginCallback(),
                    ),
                ),
                operation = "恢复微博跨域会话",
            )
            val cross = parseSsoResponse(crossResponse)
                ?: return PublisherLoginResult(PublisherLoginStatus.FAILED, "微博会话恢复跨域响应无效")
            val urls = cross.array("arrURL").toUrlList()
            if (urls.isEmpty()) {
                return PublisherLoginResult(PublisherLoginStatus.FAILED, "微博会话恢复未返回 SSO 登录地址")
            }
            completeSsoLogin(urls)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "微博会话恢复失败",
            )
        }
    }

    internal fun exportCookieHeader(): String {
        return currentCookieHeader()
    }

    suspend fun fetchHomeAccount(): PublisherLoginAccount? {
        val response = sendTextRequest(
            uri = URI.create(WEIBO_HOME),
            referer = WEIBO_HOME,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            xRequestedWith = false,
            desktopDocument = true,
        )
        if (response.statusCode() !in 200..299) {
            throw WeiboApiException("微博首页请求失败：status=${response.statusCode()}")
        }
        return parseHomeAccountResponse(response.body())
    }

    internal fun parseHomeAccountResponse(html: String): PublisherLoginAccount? {
        val root = extractJsConfigObject(html) ?: return null
        return root.obj("user")?.toPublisherSnapshot()?.toLoginAccount()
    }

    suspend fun fetchPublisherInfo(userId: String): WeiboPublisherSnapshot? {
        return parsePublisherInfoResponse(
            getJson(
                path = "/ajax/profile/info",
                parameters = listOf(
                    "uid" to userId,
                    "scene" to "1",
                ),
                referer = "$WEIBO_HOME/u/$userId",
            )
        )
    }

    internal fun parsePublisherInfoResponse(json: String): WeiboPublisherSnapshot? {
        return parsePublisherInfoResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parsePublisherInfoResponse(json: JsonElement): WeiboPublisherSnapshot? {
        val root = json.jsonObject
        if (!root.isOk()) return null
        return root.obj("data")?.obj("user")?.toPublisherSnapshot()
    }

    suspend fun fetchPublisherDetail(userId: String): WeiboPublisherSnapshot? {
        return parsePublisherDetailResponse(
            userId = userId,
            json = getJson(
                path = "/ajax/profile/detail",
                parameters = listOf("uid" to userId),
                referer = "$WEIBO_HOME/u/$userId",
            )
        )
    }

    internal fun parsePublisherDetailResponse(
        userId: String,
        json: String,
    ): WeiboPublisherSnapshot? {
        return parsePublisherDetailResponse(userId, WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parsePublisherDetailResponse(
        userId: String,
        json: JsonElement,
    ): WeiboPublisherSnapshot? {
        val root = json.jsonObject
        if (!root.isOk()) return null
        val data = root.obj("data") ?: return null
        return WeiboPublisherSnapshot(
            userId = userId,
            screenName = data.obj("real_name")?.string("name") ?: userId,
            description = data.string("description") ?: data.string("desc_text"),
            location = data.string("ip_location"),
        )
    }

    internal fun parseUserTimelineResponse(json: String): WeiboTimelinePage {
        return parseUserTimelineResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parseUserTimelineResponse(json: JsonElement): WeiboTimelinePage {
        val root = json.jsonObject
        if (!root.isOk()) return WeiboTimelinePage()
        val data = root.obj("data") ?: return WeiboTimelinePage()
        return WeiboTimelinePage(
            posts = data.array("list").mapNotNull { it.asObject()?.toPostSnapshot() },
            nextCursor = data.string("since_id") ?: data.long("since_id")?.toString(),
        )
    }

    suspend fun fetchPostDetail(postId: String): WeiboPostSnapshot? {
        return parsePostDetailResponse(
            getJson(
                path = "/ajax/statuses/show",
                parameters = listOf(
                    "id" to postId,
                    "locale" to "zh-CN",
                    "isGetLongText" to "true",
                ),
                referer = "$WEIBO_HOME/detail/$postId",
            )
        )
    }

    suspend fun downloadVideoLink(request: LinkVideoDownloadRequest): LinkVideoDownloadResult {
        val postId = request.parsedLink.targetId.trim()
        require(postId.isNotBlank()) { "微博动态 ID 不能为空" }
        val post = fetchPostDetail(postId) ?: error("未找到微博视频动态：$postId")
        val video = post.findDownloadableVideo()
            ?: error("微博动态未提供可下载的视频地址：$postId")
        return videoDownloader.download(
            request = request,
            video = video,
            referer = "$WEIBO_HOME/detail/$postId",
        )
    }

    internal fun parsePostDetailResponse(json: String): WeiboPostSnapshot? {
        return parsePostDetailResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parsePostDetailResponse(json: JsonElement): WeiboPostSnapshot? {
        val root = json.jsonObject
        if (!root.isOk()) return null
        return root.toPostSnapshot()
    }

    suspend fun fetchLongText(postId: String): WeiboLongTextSnapshot? {
        return parseLongTextResponse(
            getJson(
                path = "/ajax/statuses/longtext",
                parameters = listOf("id" to postId),
                referer = "$WEIBO_HOME/detail/$postId",
            )
        )
    }

    internal fun parseLongTextResponse(json: String): WeiboLongTextSnapshot? {
        return parseLongTextResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parseLongTextResponse(json: JsonElement): WeiboLongTextSnapshot? {
        val root = json.jsonObject
        if (!root.isOk()) return null
        val data = root.obj("data") ?: return null
        val rawContent = data.string("longTextContent_raw")
            ?: data.string("longTextContent")
            ?: return null
        val content = rawContent.toPlainWeiboText()
        return WeiboLongTextSnapshot(
            content = content,
            rawContent = rawContent,
        )
    }

    suspend fun queryFollowState(userId: String): FollowState {
        val profile = fetchPublisherInfo(userId) ?: return FollowState.NOT_FOLLOWING
        return if (profile.following == true) FollowState.FOLLOWING else FollowState.NOT_FOLLOWING
    }

    suspend fun followPublisher(userId: String): FollowActionResult {
        if (queryFollowState(userId) == FollowState.FOLLOWING) {
            return FollowActionResult(FollowActionStatus.NOOP, "已关注微博用户：$userId")
        }
        val root = postForm(
            path = "/ajax/friendships/create",
            parameters = listOf(
                "friend_uid" to userId,
                "page" to "profile",
                "lpage" to "homeRecom",
            ),
            referer = "$WEIBO_HOME/u/$userId",
        ).jsonObject
        return parseFollowActionResponse(
            root = root,
            expectedFollowing = true,
            successMessage = "已关注微博用户：$userId",
            failureMessage = "微博关注失败：$userId",
        )
    }

    suspend fun unfollowPublisher(userId: String): FollowActionResult {
        val root = postForm(
            path = "/ajax/friendships/destory",
            parameters = listOf("uid" to userId),
            referer = "$WEIBO_HOME/u/$userId",
        ).jsonObject
        return parseFollowActionResponse(
            root = root,
            expectedFollowing = false,
            successMessage = "已取消关注微博用户：$userId",
            failureMessage = "微博取消关注失败：$userId",
        )
    }

    internal fun parseFollowActionResponse(
        json: String,
        expectedFollowing: Boolean,
        successMessage: String = "微博关注状态已更新",
        failureMessage: String = "微博关注状态更新失败",
    ): FollowActionResult {
        return parseFollowActionResponse(
            root = WEIBO_JSON.parseToJsonElement(json).jsonObject,
            expectedFollowing = expectedFollowing,
            successMessage = successMessage,
            failureMessage = failureMessage,
        )
    }

    suspend fun fetchAssignedFollowGroups(userId: String): List<WeiboFollowGroupSnapshot> {
        return parseAssignedFollowGroupsResponse(
            getJson(
                path = "/ajax/profile/getGroupList",
                parameters = listOf("uid" to userId),
                referer = "$WEIBO_HOME/u/$userId",
            )
        )
    }

    internal fun parseAssignedFollowGroupsResponse(json: String): List<WeiboFollowGroupSnapshot> {
        return parseAssignedFollowGroupsResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parseAssignedFollowGroupsResponse(json: JsonElement): List<WeiboFollowGroupSnapshot> {
        val root = json.jsonObject
        if (!root.isOk()) return emptyList()
        return root.array("data").mapNotNull { it.asObject()?.toFollowGroupSnapshot() }
    }

    suspend fun fetchAvailableFollowGroups(userId: String): List<WeiboFollowGroupSnapshot> {
        return parseAvailableFollowGroupsResponse(
            getJson(
                path = "/ajax/profile/getGroups",
                parameters = listOf(
                    "target_uid" to userId,
                    "filterType" to "system",
                    "hasRecom" to "true",
                ),
                referer = "$WEIBO_HOME/u/$userId",
            )
        )
    }

    internal fun parseAvailableFollowGroupsResponse(json: String): List<WeiboFollowGroupSnapshot> {
        return parseAvailableFollowGroupsResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parseAvailableFollowGroupsResponse(json: JsonElement): List<WeiboFollowGroupSnapshot> {
        val root = json.jsonObject
        if (!root.isOk()) return emptyList()
        return root.obj("data")
            ?.array("lists")
            ?.mapNotNull { it.asObject()?.toFollowGroupSnapshot() }
            .orEmpty()
    }

    suspend fun createFollowGroup(
        name: String,
        isPublic: Boolean = false,
    ): WeiboFollowGroupSnapshot? {
        val parameters = buildList {
            add("name" to name)
            if (isPublic) add("isOpen" to "true")
        }
        return parseCreateFollowGroupResponse(
            postForm(
                path = "/ajax/profile/createGroup",
                parameters = parameters,
                referer = WEIBO_HOME,
            )
        )
    }

    internal fun parseCreateFollowGroupResponse(json: String): WeiboFollowGroupSnapshot? {
        return parseCreateFollowGroupResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parseCreateFollowGroupResponse(json: JsonElement): WeiboFollowGroupSnapshot? {
        val root = json.jsonObject
        if (!root.isOk()) return null
        return root.obj("data")?.toFollowGroupSnapshot()
    }

    suspend fun setFollowGroups(
        userId: String,
        listIds: List<String>,
        originListIds: List<String>,
    ): Boolean {
        return parseSetFollowGroupsResponse(
            postForm(
                path = "/ajax/profile/setGroup",
                parameters = listOf(
                    "uids" to userId,
                    "list_ids" to listIds.joinToString(","),
                    "origin_list_ids" to originListIds.joinToString(","),
                ),
                referer = "$WEIBO_HOME/u/$userId",
            )
        )
    }

    internal fun parseSetFollowGroupsResponse(json: String): Boolean {
        return parseSetFollowGroupsResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parseSetFollowGroupsResponse(json: JsonElement): Boolean {
        val root = json.jsonObject
        if (!root.isOk()) return false
        return root.obj("data")?.boolean("result") ?: root.boolean("result") ?: true
    }

    suspend fun currentAccountFriendTimelineListId(): String? {
        return friendTimelineListIdForAccount(fetchCurrentAccount())
    }

    internal fun friendTimelineListIdForAccount(account: PublisherLoginAccount?): String? {
        val uid = account?.userId
            ?.takeIf { it.isWeiboUid() }
            ?: return null
        return "$DEFAULT_FRIEND_TIMELINE_LIST_PREFIX$uid"
    }

    suspend fun fetchDefaultFriendTimelineListId(): String? {
        return parseDefaultFriendTimelineListIdResponse(
            getJson(
                path = "/ajax/feed/allGroups",
                parameters = emptyList(),
                referer = WEIBO_HOME,
            )
        )
    }

    internal fun parseDefaultFriendTimelineListIdResponse(json: String): String? {
        return parseDefaultFriendTimelineListIdResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parseDefaultFriendTimelineListIdResponse(json: JsonElement): String? {
        val root = json.jsonObject
        if (!root.isOk()) return null
        val groups = root.array("groups")
            .flatMap { section -> section.asObject()?.array("group").orEmpty() }
            .mapNotNull { it.asObject() }
        return groups.firstOrNull { group ->
            group.string("title") == DEFAULT_FRIEND_TIMELINE_TITLE &&
                group.string("apipath") == FRIEND_TIMELINE_API_PATH
        }?.string("gid")
            ?: groups.firstOrNull { group ->
                group.string("gid")?.startsWith(DEFAULT_FRIEND_TIMELINE_LIST_PREFIX) == true &&
                    group.string("apipath") == FRIEND_TIMELINE_API_PATH
            }?.string("gid")
    }

    suspend fun fetchFriendTimeline(
        listId: String? = null,
        sinceId: String = "0",
        count: Int = 25,
        fid: String? = listId,
        refresh: Int = 4,
    ): WeiboTimelinePage {
        val parameters = buildList {
            listId?.takeIf(String::isNotBlank)?.let { add("list_id" to it) }
            add("refresh" to refresh.toString())
            add("since_id" to sinceId)
            add("count" to count.toString())
            fid?.takeIf(String::isNotBlank)?.let { add("fid" to it) }
        }
        return parseFriendTimelineResponse(
            getJson(
                path = "/ajax/feed/friendstimeline",
                parameters = parameters,
                referer = WEIBO_HOME,
            )
        )
    }

    internal fun parseFriendTimelineResponse(json: String): WeiboTimelinePage {
        return parseFriendTimelineResponse(WEIBO_JSON.parseToJsonElement(json))
    }

    internal fun parseFriendTimelineResponse(json: JsonElement): WeiboTimelinePage {
        val root = json.jsonObject
        if (!root.isOk()) return WeiboTimelinePage()
        return WeiboTimelinePage(
            posts = root.array("statuses").mapNotNull { it.asObject()?.toPostSnapshot() },
            nextCursor = root.string("since_id_str")
                ?: root.string("since_id")
                ?: root.long("since_id")?.toString(),
        )
    }

    internal fun parseSsoResponse(body: String): JsonObject? {
        val trimmed = body.trim()
        runCatching { WEIBO_JSON.parseToJsonElement(trimmed).asObject() }
            .getOrNull()
            ?.let { return it }

        val startIndex = trimmed.indexOf('(')
        val endIndex = trimmed.lastIndexOf(')')
        if (startIndex < 0 || endIndex <= startIndex) return null
        return runCatching {
            WEIBO_JSON.parseToJsonElement(trimmed.substring(startIndex + 1, endIndex)).asObject()
        }.getOrNull()
    }

    internal fun parseQrCodeResponse(body: String): WeiboQrCodeSnapshot? {
        val root = parseSsoResponse(body) ?: return null
        if (root.long("retcode") != QR_LOGIN_SUCCESS_CODE) return null
        val data = root.obj("data") ?: return null
        val id = data.string("qrid") ?: return null
        val imageUrl = data.string("image") ?: return null
        return WeiboQrCodeSnapshot(id = id, imageUrl = imageUrl)
    }

    internal fun parseQrLoginPollResponse(body: String): WeiboQrLoginPollSnapshot? {
        val root = parseSsoResponse(body) ?: return null
        val code = root.long("retcode") ?: return null
        val data = root.obj("data")
        return WeiboQrLoginPollSnapshot(
            code = code,
            message = root.string("msg") ?: root.string("message").orEmpty(),
            loginUrl = data?.string("url"),
        )
    }

    internal fun parseQrCodeImageUri(imageUrl: String): URI? {
        val value = imageUrl.trim().takeIf(String::isNotBlank) ?: return null
        val normalized = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://$QR_IMAGE_HOST$value"
            "://" !in value -> "https://$QR_IMAGE_HOST/$value"
            else -> value
        }
        val uri = runCatching { URI.create(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in QR_IMAGE_HOSTS || (uri.port != -1 && uri.port != 80 && uri.port != 443)) {
            return null
        }
        return when (uri.scheme?.lowercase()) {
            "https" -> uri
            "http" -> URI("https", uri.userInfo, host, -1, uri.path, uri.query, null)
            else -> null
        }
    }

    internal fun parseQrLoginUri(loginUrl: String): URI? {
        val uri = runCatching { URI.create(loginUrl.trim()) }.getOrNull() ?: return null
        if (
            uri.scheme?.equals("https", ignoreCase = true) != true ||
            !uri.host.equals(PASSPORT_HOST, ignoreCase = true) ||
            uri.port != -1 ||
            uri.path != SSO_QRCODE_LOGIN_PATH
        ) {
            return null
        }
        return uri
    }

    internal fun parseSsoMetaRedirect(html: String): String? {
        val match = SSO_META_REDIRECT_PATTERN.find(html) ?: return null
        return match.groupValues[2]
            .replace("&amp;", "&")
            .takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private suspend fun requestQrLoginPage() {
        val uri = URI.create(PASSPORT_QR_SIGN_IN_URI)
        val response = sendTextRequest(
            uri = uri,
            referer = WEIBO_HOME,
            accept = LOGIN_DOCUMENT_ACCEPT,
            xRequestedWith = false,
            desktopDocument = true,
        )
        if (response.statusCode() !in 200..299) {
            throw WeiboApiException("微博二维码登录初始化失败：status=${response.statusCode()}")
        }
    }

    private suspend fun requestQrCode(): WeiboQrCodeSnapshot {
        val response = sendQrLoginRequest(
            uri = buildExternalUri(
                SSO_QRCODE_IMAGE_URI,
                listOf(
                    "entry" to "miniblog",
                    "size" to QR_LOGIN_SIZE.toString(),
                ),
            ),
            operation = "获取微博二维码",
        )
        return parseQrCodeResponse(response)
            ?: throw WeiboApiException("微博二维码响应无效")
    }

    private suspend fun requestQrLoginStatus(qrId: String): WeiboQrLoginPollSnapshot {
        val response = sendQrLoginRequest(
            uri = buildExternalUri(
                SSO_QRCODE_CHECK_URI,
                listOf(
                    "entry" to "miniblog",
                    "source" to "miniblog",
                    "url" to QR_LOGIN_RETURN_URL,
                    "qrid" to qrId,
                    "disp" to "popup",
                ),
            ),
            operation = "检查微博二维码状态",
        )
        return parseQrLoginPollResponse(response)
            ?: throw WeiboApiException("微博二维码状态响应无效")
    }

    private suspend fun downloadQrCodeImage(imageUrl: String): ByteArray {
        val imageUri = parseQrCodeImageUri(imageUrl)
            ?: throw WeiboApiException("微博二维码图片地址无效")
        val response = sendImageRequest(imageUri)
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        return response.body().use { input ->
            if (response.statusCode() !in 200..299) {
                throw WeiboApiException("下载微博二维码图片失败：status=${response.statusCode()}")
            }
            if (!contentType.isPngContentType()) {
                throw WeiboApiException("微博二维码图片响应类型无效：contentType=$contentType")
            }
            response.headers()
                .firstValue("Content-Length")
                .orElse(null)
                ?.toLongOrNull()
                ?.takeIf { it > MAX_QR_IMAGE_BYTES }
                ?.let { size -> throw WeiboApiException("微博二维码图片超过大小限制：size=$size") }

            val bytes = input.readBytesLimited(MAX_QR_IMAGE_BYTES)
            if (bytes.isEmpty()) {
                throw WeiboApiException("微博二维码图片为空")
            }
            if (!bytes.hasPngSignature()) {
                throw WeiboApiException("微博二维码图片不是 PNG 格式")
            }
            bytes
        }
    }

    private suspend fun completeQrLogin(poll: WeiboQrLoginPollSnapshot): PublisherLoginResult {
        val loginUri = poll.loginUrl?.let(::parseQrLoginUri)
        if (loginUri == null) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "微博二维码确认响应未提供登录地址",
            )
        }
        val response = sendTextRequest(
            uri = loginUri,
            referer = PASSPORT_QR_SIGN_IN_URI,
            accept = LOGIN_DOCUMENT_ACCEPT,
            xRequestedWith = false,
            desktopDocument = true,
        )
        if (response.statusCode() !in 200..299) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "微博二维码会话登录失败：status=${response.statusCode()}",
            )
        }
        cachedLoginAccount = null
        return checkLoginState()
    }

    private suspend fun requestSsoRestoreToken(): WeiboSsoToken? {
        val response = sendJsonpRequest(
            uri = buildExternalUri(
                PASSPORT_VISITOR_URI,
                listOf(
                    "a" to "restore",
                    "cb" to "restore_back",
                    "from" to "weibo",
                    "_rand" to System.currentTimeMillis().toString(),
                ),
            ),
            operation = "获取微博会话恢复令牌",
        )
        val root = parseSsoResponse(response) ?: return null
        if (root.long("retcode") != QR_LOGIN_SUCCESS_CODE) return null
        val data = root.obj("data") ?: return null
        val alt = data.string("alt") ?: return null
        return WeiboSsoToken(
            alt = alt,
            saveState = data.long("savestate")?.toInt() ?: DEFAULT_SSO_SAVE_STATE,
        )
    }

    private suspend fun completeSsoLogin(crossDomainUrls: List<String>): PublisherLoginResult {
        val ssoLoginUrl = crossDomainUrls
            .firstOrNull { url -> runCatching { URI.create(url) }.getOrNull()?.host == PASSPORT_HOST }
            ?: return PublisherLoginResult(PublisherLoginStatus.FAILED, "微博 SSO 会话缺少登录地址")
        val ssoResponse = sendTextRequest(
            uri = buildExternalUri(
                ssoLoginUrl,
                listOf(
                    "action" to "login",
                    "callback" to loginCallback(),
                ),
            ),
            referer = WEIBO_HOME,
            accept = LOGIN_ACCEPT,
            xRequestedWith = false,
        )
        if (ssoResponse.statusCode() !in 200..299) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "微博二维码 SSO 登录失败：status=${ssoResponse.statusCode()}",
            )
        }
        cachedLoginAccount = null
        return checkLoginState()
    }

    private suspend fun sendJsonpRequest(
        uri: URI,
        operation: String,
    ): String {
        val response = sendTextRequest(
            uri = uri,
            referer = WEIBO_HOME,
            accept = LOGIN_ACCEPT,
            xRequestedWith = false,
        )
        if (response.statusCode() !in 200..299) {
            throw WeiboApiException("${operation}失败：status=${response.statusCode()}")
        }
        return response.body()
    }

    private suspend fun sendQrLoginRequest(
        uri: URI,
        operation: String,
    ): String {
        val response = sendTextRequest(
            uri = uri,
            referer = PASSPORT_QR_SIGN_IN_URI,
            accept = QR_LOGIN_ACCEPT,
            xRequestedWith = true,
        )
        if (response.statusCode() !in 200..299) {
            throw WeiboApiException("${operation}失败：status=${response.statusCode()}")
        }
        return response.body()
    }

    private suspend fun getJson(
        path: String,
        parameters: List<Pair<String, String>>,
        referer: String,
    ): JsonElement {
        val uri = buildUri(path, parameters)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                currentCookieHeader().takeIf(String::isNotBlank)?.let { cookie ->
                    header("Cookie", cookie)
                    cookie.xsrfToken()?.let { token ->
                        header("X-XSRF-TOKEN", token)
                        if (uri.host.equals(PASSPORT_HOST, ignoreCase = true)) {
                            header("X-CSRF-TOKEN", token)
                        }
                    }
                }
            }
            .GET()
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
        return parseHttpJsonResponse(
            statusCode = response.statusCode(),
            path = path,
            body = response.body(),
        )
    }

    private suspend fun sendTextRequest(
        uri: URI,
        referer: String,
        accept: String,
        xRequestedWith: Boolean = true,
        desktopDocument: Boolean = false,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", referer)
            .apply {
                if (xRequestedWith) {
                    header("X-Requested-With", "XMLHttpRequest")
                }
                if (desktopDocument) {
                    header("Cache-Control", "max-age=0")
                    header("Upgrade-Insecure-Requests", "1")
                    header("Sec-CH-UA", "\"Microsoft Edge\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"")
                    header("Sec-CH-UA-Mobile", "?0")
                    header("Sec-CH-UA-Platform", "\"Windows\"")
                    header("Sec-Fetch-Dest", "document")
                    header("Sec-Fetch-Mode", "navigate")
                    header("Sec-Fetch-Site", "same-origin")
                    header("Sec-Fetch-User", "?1")
                }
                currentCookieHeader().takeIf(String::isNotBlank)?.let { cookie ->
                    header("Cookie", cookie)
                    cookie.xsrfToken()?.let { header("X-XSRF-TOKEN", it) }
                }
            }
            .GET()
            .build()

        return withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
    }

    private suspend fun sendImageRequest(uri: URI): HttpResponse<InputStream> {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", DESKTOP_USER_AGENT)
            // 二维码 URL 自带签名，无需向图片域名转发微博登录 Cookie。
            .header("Referer", PASSPORT_QR_SIGN_IN_URI)
            .GET()
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        }
    }

    private suspend fun postForm(
        path: String,
        parameters: List<Pair<String, String>>,
        referer: String,
    ): JsonElement {
        val body = parameters.joinToString("&") { (name, value) ->
            "${name.urlEncode()}=${value.urlEncode()}"
        }
        val request = HttpRequest.newBuilder(URI.create("$WEIBO_HOME$path"))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                currentCookieHeader().takeIf(String::isNotBlank)?.let { cookie ->
                    header("Cookie", cookie)
                    cookie.xsrfToken()?.let { header("X-XSRF-TOKEN", it) }
                }
            }
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
        return parseHttpJsonResponse(
            statusCode = response.statusCode(),
            path = path,
            body = response.body(),
        )
    }

    internal fun parseHttpJsonResponse(
        statusCode: Int,
        path: String,
        body: String,
    ): JsonElement {
        val root = runCatching { WEIBO_JSON.parseToJsonElement(body) }
            .getOrElse { error ->
                val message = "微博接口响应不是合法 JSON：status=$statusCode，path=$path"
                if (statusCode == 401 || statusCode == 403 || body.looksLikeLoginFailure()) {
                    throw WeiboLoginException(message, error)
                }
                throw WeiboApiException(message, error)
            }

        val jsonObject = root.asObject()
        if (statusCode !in 200..299) {
            throwRequestFailure(statusCode, path, jsonObject)
        }
        if (jsonObject != null && !jsonObject.isOk()) {
            throwRequestFailure(statusCode, path, jsonObject)
        }
        return root
    }

    private fun throwRequestFailure(
        statusCode: Int,
        path: String,
        root: JsonObject?,
    ): Nothing {
        val message = root?.failureMessage() ?: "HTTP $statusCode"
        val fullMessage = "status=$statusCode，path=$path，message=$message"
        if (statusCode == 401 || statusCode == 403 || message.looksLikeLoginFailure()) {
            throw WeiboLoginException("微博登录状态不可用：$fullMessage")
        }
        throw WeiboApiException("微博接口请求失败：$fullMessage")
    }

    private fun buildUri(path: String, parameters: List<Pair<String, String>>): URI {
        val query = parameters.joinToString("&") { (name, value) ->
            "${name.urlEncode()}=${value.urlEncode()}"
        }
        return URI.create("$WEIBO_HOME$path?$query")
    }

    private fun buildExternalUri(base: String, parameters: List<Pair<String, String>>): URI {
        val query = parameters.joinToString("&") { (name, value) ->
            "${name.urlEncode()}=${value.urlEncode()}"
        }
        val separator = if ('?' in base) '&' else '?'
        return URI.create("$base$separator$query")
    }

    private fun loginCallback(): String = "STK_${System.currentTimeMillis()}"

    private fun JsonObject.toPublisherSnapshot(): WeiboPublisherSnapshot? {
        val userId = string("idstr") ?: long("id")?.toString() ?: return null
        return WeiboPublisherSnapshot(
            userId = userId,
            screenName = string("screen_name") ?: userId,
            avatarUrl = string("avatar_hd") ?: string("avatar_large") ?: string("profile_image_url"),
            coverUrl = string("cover_image_phone").firstSemicolonSeparatedValue(),
            description = string("description"),
            location = string("location"),
            verified = boolean("verified") ?: false,
            followersCount = long("followers_count"),
            friendsCount = long("friends_count"),
            statusesCount = long("statuses_count"),
            following = boolean("following"),
        )
    }

    private fun WeiboPublisherSnapshot.toLoginAccount(): PublisherLoginAccount {
        return PublisherLoginAccount(
            userId = userId,
            name = screenName,
            avatar = avatarUrl?.let { MediaRef(uri = it, kind = MediaKind.AVATAR) },
        )
    }

    private fun JsonObject.toFollowGroupSnapshot(): WeiboFollowGroupSnapshot? {
        val id = string("idstr") ?: string("gid") ?: long("id")?.toString() ?: return null
        val name = string("name") ?: string("title") ?: return null
        return WeiboFollowGroupSnapshot(
            id = id,
            name = name,
            mode = string("mode"),
            memberCount = long("member_count") ?: long("count"),
            exists = boolean("exist") ?: long("exist")?.let { it != 0L } ?: false,
        )
    }

    private fun JsonObject.toPostSnapshot(depth: Int = 0): WeiboPostSnapshot? {
        val user = obj("user")
        val postId = string("mblogid")
            ?: string("idstr")
            ?: long("id")?.toString()
            ?: string("mid")
            ?: return null
        val userId = user?.string("idstr")
            ?: user?.long("id")?.toString()
            ?: string("user_id")
            ?: string("uid")
            ?: UNKNOWN_WEIBO_USER_ID
        val richText = obj("longText")?.string("content")
            ?: string("text")
            ?: string("text_raw")
            ?: ""
        val cleanedText = string("text_raw")?.toPlainWeiboText()
            ?: richText.toPlainWeiboText()
        val createdAt = string("created_at")?.toWeiboEpochSeconds()
            ?: System.currentTimeMillis() / 1_000
        val pageInfo = obj("page_info") ?: obj("longText")?.obj("page_info")

        return WeiboPostSnapshot(
            postId = postId,
            userId = userId,
            screenName = user?.string("screen_name")
                ?: UNKNOWN_WEIBO_USER_NAME.takeIf { userId == UNKNOWN_WEIBO_USER_ID },
            avatarUrl = user?.string("avatar_hd") ?: user?.string("avatar_large") ?: user?.string("profile_image_url"),
            text = cleanedText,
            rawText = richText,
            createdAtEpochSeconds = createdAt,
            url = if (userId == UNKNOWN_WEIBO_USER_ID) {
                "$WEIBO_HOME/detail/$postId"
            } else {
                postLink(userId, postId)
            },
            source = string("source")?.toPlainWeiboText(),
            regionName = string("region_name"),
            isTop = boolean("isTop") ?: long("isTop")?.let { it != 0L } ?: false,
            badge = obj("title")?.string("text")?.toPlainWeiboText(),
            isLongText = boolean("isLongText") ?: false,
            pictures = toPictures(),
            card = pageInfo?.toMediaCard(),
            additionalCards = toMixedMediaCards(),
            poll = pageInfo?.toPoll(),
            reposted = if (depth < MAX_REPOST_DEPTH) {
                obj("retweeted_status")?.toPostSnapshot(depth + 1)
            } else {
                null
            },
            metrics = WeiboPostMetrics(
                reposts = long("reposts_count"),
                comments = long("comments_count"),
                likes = long("attitudes_count"),
            ),
        )
    }

    private fun JsonObject.toPictures(): List<WeiboImageSnapshot> {
        return (toPicInfoPictures() + toMixedPictures()).distinctBy { it.url }
    }

    private fun JsonObject.toPicInfoPictures(): List<WeiboImageSnapshot> {
        val picInfos = obj("pic_infos") ?: return emptyList()
        val orderedIds = array("pic_ids")
            .mapNotNull { it.asPrimitive()?.contentOrNull }
            .filter { it.isNotBlank() }
        return if (orderedIds.isNotEmpty()) {
            orderedIds.mapNotNull { id -> picInfos.obj(id)?.toPicture(id) }
        } else {
            picInfos.values.mapNotNull { it.asObject()?.toPicture(null) }
        }
    }

    private fun JsonObject.toMixedPictures(): List<WeiboImageSnapshot> {
        return obj("mix_media_info")
            ?.array("items")
            ?.mapNotNull { item ->
                val root = item.asObject() ?: return@mapNotNull null
                if (root.string("type") != "pic") return@mapNotNull null
                root.obj("data")?.toPicture(root.string("id"))
            }
            .orEmpty()
    }

    private fun JsonObject.toMixedMediaCards(): List<WeiboMediaCardSnapshot> {
        return obj("mix_media_info")
            ?.array("items")
            ?.mapNotNull { item ->
                val root = item.asObject() ?: return@mapNotNull null
                if (root.string("type") == "pic") return@mapNotNull null
                root.obj("data")?.toMediaCard()
            }
            .orEmpty()
    }

    private fun JsonObject.toPicture(picId: String?): WeiboImageSnapshot? {
        val image = obj("largest")
            ?: obj("large")
            ?: obj("original")
            ?: obj("mw2000")
            ?: obj("bmiddle")
            ?: obj("thumbnail")
            ?: return null
        val url = image.string("url") ?: return null
        return WeiboImageSnapshot(
            url = url,
            width = image.long("width")?.toInt(),
            height = image.long("height")?.toInt(),
            badge = "GIF".takeIf { string("type") == "gif" },
            alt = string("photo_tag") ?: picId,
        )
    }

    private fun JsonObject.toMediaCard(): WeiboMediaCardSnapshot? {
        if (isPollPageInfo()) return null
        val media = obj("media_info")
        val objectType = string("object_type")
        val kind = when {
            objectType == "video" || media?.string("media_type") == "video" -> WeiboMediaCardKind.VIDEO
            objectType == "article" -> WeiboMediaCardKind.ARTICLE
            objectType == "live" -> WeiboMediaCardKind.LIVE
            objectType == "product" -> WeiboMediaCardKind.PRODUCT
            else -> WeiboMediaCardKind.LINK
        }
        val title = when (kind) {
            WeiboMediaCardKind.VIDEO -> media?.string("video_title")
                ?: media?.string("kol_title")
                ?: string("page_title")
                ?: string("content1")
                ?: media?.string("name")
            WeiboMediaCardKind.ARTICLE -> string("content1")
                ?: string("page_title")
            WeiboMediaCardKind.LIVE -> string("content2")
                ?: string("page_desc")
                ?: string("page_title")
                ?: string("content1")
            else -> string("page_title")
                ?: string("content1")
                ?: media?.string("name")
                ?: media?.string("video_title")
        } ?: return null
        val coverUrl = string("page_pic")
            ?: obj("pic_info")?.bestPictureUrl()
            ?: media?.obj("big_pic_info")?.bestPictureUrl()
        val url = firstHttpUrl(
            string("page_url"),
            string("short_url"),
            media?.string("h5_url"),
            media?.string("jump_to"),
        )
        val videoSources = media?.videoSources().orEmpty()
        return WeiboMediaCardSnapshot(
            kind = kind,
            id = string("object_id") ?: string("page_id") ?: media?.string("media_id"),
            title = title.toPlainWeiboText(),
            description = when (kind) {
                WeiboMediaCardKind.LIVE -> string("page_title")
                    ?.takeIf { it != title }
                    ?: string("content1")
                WeiboMediaCardKind.ARTICLE -> string("content2")
                    ?: string("content3")
                    ?: string("page_desc")
                else -> string("content2")
                    ?: media?.string("kol_title")?.takeIf { it != title }
                    ?: string("page_desc")
            }.orEmpty().toPlainWeiboText(),
            info = when (kind) {
                WeiboMediaCardKind.VIDEO -> media?.string("online_users")
                else -> null
            }?.toPlainWeiboText(),
            coverUrl = coverUrl,
            mediaUrl = videoSources.bestVideoUrl(),
            videoSources = videoSources,
            durationSeconds = media?.long("duration"),
            url = url,
        )
    }

    private fun JsonObject.toPoll(): WeiboPollSnapshot? {
        if (!isPollPageInfo()) return null
        val vote = obj("card_info")?.obj("vote_object") ?: return null
        val title = vote.string("content")
            ?: string("page_title")
            ?: return null
        val options = vote.array("vote_list").mapNotNull { item ->
            item.asObject()?.toPollOption()
        }
        val expireAt = vote.long("expire_date")
        return WeiboPollSnapshot(
            id = vote.string("id") ?: string("object_id") ?: string("page_id"),
            title = title.toPlainWeiboText(),
            options = options,
            status = when {
                expireAt != null && expireAt < System.currentTimeMillis() / 1_000 -> WeiboPollStatus.CLOSED
                vote.long("state") == 1L -> WeiboPollStatus.OPEN
                else -> WeiboPollStatus.UNKNOWN
            },
            link = firstHttpUrl(
                string("page_url"),
                vote.string("target_url"),
                vote.string("report_url"),
            ),
        )
    }

    private fun JsonObject.toPollOption(): WeiboPollOptionSnapshot? {
        val text = string("content") ?: return null
        val votes = long("part_num")
        return WeiboPollOptionSnapshot(
            id = string("id"),
            text = text.toPlainWeiboText(),
            votes = votes,
            displayVotes = votes?.let { "$it 票" },
        )
    }

    private fun JsonObject.isPollPageInfo(): Boolean {
        return string("object_type") == "hudongvote" ||
            obj("card_info")?.obj("vote_object") != null
    }

    private fun JsonObject.videoSources(): List<WeiboVideoSourceSnapshot> {
        return buildList {
            array("playback_list").forEach { item ->
                val root = item.asObject() ?: return@forEach
                val meta = root.obj("meta")
                val url = root.obj("play_info")?.string("url") ?: return@forEach
                addVideoSource(meta?.long("quality_index")?.toInt() ?: 0, url)
            }
            addVideoSource(1080, string("mp4_1080p_mp4"))
            addVideoSource(720, string("mp4_720p_mp4"))
            addVideoSource(720, string("stream_url_hd"))
            addVideoSource(480, string("stream_url"))
            addVideoSource(720, string("mp4_hd_url"))
            addVideoSource(480, string("mp4_sd_url"))
        }.distinctBy { it.url }
    }

    private fun MutableList<WeiboVideoSourceSnapshot>.addVideoSource(quality: Int, url: String?) {
        url?.takeIf { it.isHttpUrl() }?.let { add(WeiboVideoSourceSnapshot(quality, it)) }
    }

    private fun List<WeiboVideoSourceSnapshot>.bestVideoUrl(): String? {
        return maxWithOrNull(compareBy<WeiboVideoSourceSnapshot> { it.quality }.thenBy { it.url })?.url
    }

    private fun JsonObject.bestPictureUrl(): String? {
        return obj("pic_big")?.string("url")
            ?: obj("pic_middle")?.string("url")
            ?: obj("pic_small")?.string("url")
            ?: string("url")
    }

    private fun JsonObject.isOk(): Boolean {
        return when (val ok = get("ok")) {
            null -> true
            is JsonPrimitive -> ok.booleanOrNull ?: ok.longOrNull?.let { it != 0L } ?: false
            else -> false
        }
    }

    private fun JsonObject.isExplicitOk(): Boolean {
        return when (val ok = get("ok")) {
            is JsonPrimitive -> ok.booleanOrNull ?: ok.longOrNull?.let { it != 0L } ?: false
            else -> false
        }
    }

    private fun parseFollowActionResponse(
        root: JsonObject,
        expectedFollowing: Boolean,
        successMessage: String,
        failureMessage: String,
    ): FollowActionResult {
        if (root.isOk() && root.string("error").isNullOrBlank()) {
            return FollowActionResult(FollowActionStatus.DONE, successMessage)
        }
        val snapshot = root.toPublisherSnapshot()
        if (snapshot?.following == expectedFollowing) {
            return FollowActionResult(FollowActionStatus.DONE, successMessage)
        }
        val message = root.string("msg") ?: root.string("message") ?: failureMessage
        return FollowActionResult(FollowActionStatus.FAILED, message)
    }

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.asObject()

    private fun JsonObject.array(name: String): JsonArray = get(name)?.asArray() ?: JsonArray(emptyList())

    private fun JsonObject.string(name: String): String? {
        return get(name)?.asPrimitive()?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.long(name: String): Long? {
        return when (val value = get(name)) {
            is JsonPrimitive -> value.longOrNull ?: value.contentOrNull?.toLongOrNull()
            else -> null
        }
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        return when (val value = get(name)) {
            is JsonPrimitive -> value.booleanOrNull
            else -> null
        }
    }

    private fun JsonObject.failureMessage(): String? {
        return string("message")
            ?: string("msg")
            ?: string("error")
            ?: obj("data")?.string("message")
            ?: obj("data")?.string("msg")
            ?: obj("data")?.string("error")
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement.asArray(): JsonArray? = this as? JsonArray

    private fun JsonElement.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonArray.toUrlList(): List<String> {
        return mapNotNull { item ->
            item.asPrimitive()?.contentOrNull ?: item.asObject()?.string("url")
        }.filter { url ->
            url.startsWith("https://") || url.startsWith("http://")
        }
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8)
    }

    private fun String.xsrfToken(): String? {
        return split(';')
            .mapNotNull { raw ->
                val index = raw.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val name = raw.substring(0, index).trim()
                val value = raw.substring(index + 1).trim()
                if (name == "XSRF-TOKEN") URLDecoder.decode(value, StandardCharsets.UTF_8) else null
            }
            .firstOrNull { it.isNotBlank() }
    }

    private fun String.isPngContentType(): Boolean {
        return substringBefore(';').trim().equals("image/png", ignoreCase = true)
    }

    private fun ByteArray.hasPngSignature(): Boolean {
        return size >= PNG_SIGNATURE.size && indices.take(PNG_SIGNATURE.size).all { index -> this[index] == PNG_SIGNATURE[index] }
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, QR_IMAGE_READ_BUFFER_SIZE))
        val buffer = ByteArray(QR_IMAGE_READ_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() > maxBytes - count) {
                throw WeiboApiException("微博二维码图片超过大小限制：maxBytes=$maxBytes")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun hasCookie(name: String): Boolean {
        return currentCookieHeader()
            .split(';')
            .any { item -> item.substringBefore('=').trim() == name }
    }

    private fun String.toPlainWeiboText(): String {
        return replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .htmlDecode()
            .trim()
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

    private fun String.looksLikeLoginFailure(): Boolean {
        val value = lowercase()
        return value.contains("登录") ||
            value.contains("登陆") ||
            value.contains("未登录") ||
            value.contains("login") ||
            value.contains("passport") ||
            value.contains("sso") ||
            value.contains("auth") ||
            value.contains("cookie")
    }

    private fun String.isWeiboUid(): Boolean {
        return isNotBlank() && all(Char::isDigit)
    }

    private fun String.toWeiboEpochSeconds(): Long? {
        val value = trim().takeIf { it.isNotBlank() } ?: return null
        return value.toLongOrNull()
            ?: runCatching {
                ZonedDateTime.parse(value, WEIBO_TIME_FORMATTER).toEpochSecond()
            }.getOrNull()
    }

    private fun firstHttpUrl(vararg urls: String?): String? {
        return urls.firstOrNull { url ->
            url?.startsWith("http://") == true || url?.startsWith("https://") == true
        } ?: urls.firstOrNull { !it.isNullOrBlank() }
    }

    private fun String?.firstSemicolonSeparatedValue(): String? {
        return this
            ?.splitToSequence(';')
            ?.map(String::trim)
            ?.firstOrNull(String::isNotBlank)
    }

    private suspend fun fetchCurrentAccount(forceRefresh: Boolean = false): PublisherLoginAccount? {
        if (!forceRefresh) {
            cachedLoginAccount?.let { return it }
        }
        val account = try {
            fetchHomeAccount()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.debug(error) { "微博当前账号 PC 首页解析异常" }
            null
        }
        if (account == null) {
            cachedLoginAccount = null
            logger.debug { "微博当前账号识别失败：PC 首页未包含 window.\$CONFIG.user" }
            return null
        }
        cachedLoginAccount = account
        logger.info {
            "微博当前账号识别成功：source=pc_home，uid=${account.userId ?: "未知"}，name=${account.name ?: "未知"}"
        }
        return account
    }

    private fun currentCookieHeader(): String {
        return mergeCookieHeaders(config.cookie, cookieStoreHeader())
    }

    private fun cookieStoreHeader(): String {
        val cookieManager = httpClient.cookieHandler().orElse(null) as? CookieManager ?: return ""
        return cookieManager.cookieStore.cookies
            .asSequence()
            .filterNot { it.hasExpired() }
            .filter { it.name.isNotBlank() }
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }

    private fun mergeCookieHeaders(vararg headers: String?): String {
        val values = LinkedHashMap<String, String>()
        headers.forEach { header ->
            header?.split(';')
                ?.mapNotNull { raw ->
                    val index = raw.indexOf('=')
                    if (index <= 0) return@mapNotNull null
                    val name = raw.substring(0, index).trim()
                    val value = raw.substring(index + 1).trim()
                    if (name.isBlank()) return@mapNotNull null
                    name to value
                }
                ?.forEach { (name, value) -> values[name] = value }
        }
        return values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    private fun extractJsConfigObject(html: String): JsonObject? {
        val markerIndex = html.indexOf(WINDOW_CONFIG_MARKER)
        if (markerIndex < 0) return null
        val assignIndex = html.indexOf('=', markerIndex)
        if (assignIndex < 0) return null
        val startIndex = html.indexOf('{', assignIndex)
        if (startIndex < 0) return null
        val rawJson = extractBalancedObject(html, startIndex) ?: return null
        return runCatching { WEIBO_JSON.parseToJsonElement(rawJson).asObject() }.getOrNull()
    }

    private fun extractBalancedObject(source: String, startIndex: Int): String? {
        var depth = 0
        var inString = false
        var quote = '\u0000'
        var escaping = false
        for (index in startIndex until source.length) {
            val char = source[index]
            if (inString) {
                when {
                    escaping -> escaping = false
                    char == '\\' -> escaping = true
                    char == quote -> inString = false
                }
                continue
            }

            when (char) {
                '"',
                '\'' -> {
                    inString = true
                    quote = char
                }
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(startIndex, index + 1)
                    }
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    private companion object {
        private const val MAX_REPOST_DEPTH: Int = 2
        private const val DEFAULT_FRIEND_TIMELINE_LIST_PREFIX: String = "11000"
        private const val DEFAULT_FRIEND_TIMELINE_TITLE: String = "最新微博"
        private const val FRIEND_TIMELINE_API_PATH: String = "statuses/friends/timeline"
        private const val PASSPORT_HOST: String = "passport.weibo.com"
        private const val LOGIN_SINA_HOST: String = "login.sina.com.cn"
        private const val QR_IMAGE_HOST: String = "v2.qr.weibo.cn"
        private const val PASSPORT_VISITOR_URI: String = "https://passport.weibo.com/visitor/visitor"
        private const val PASSPORT_QR_SIGN_IN_URI: String =
            "https://passport.weibo.com/sso/signin?entry=miniblog&source=miniblog&disp=popup&url=https%3A%2F%2Fweibo.com%2Fnewlogin%3Ftabtype%3Dweibo%26gid%3D102803%26openLoginLayer%3D0%26url%3Dhttps%3A%2F%2Fweibo.com%2F&from=weibopro"
        private const val SSO_QRCODE_IMAGE_URI: String = "https://passport.weibo.com/sso/v2/qrcode/image"
        private const val SSO_QRCODE_CHECK_URI: String = "https://passport.weibo.com/sso/v2/qrcode/check"
        private const val SSO_QRCODE_LOGIN_URI: String = "https://passport.weibo.com/sso/v2/login"
        private const val SSO_QRCODE_LOGIN_PATH: String = "/sso/v2/login"
        private const val SSO_LOGIN_URI: String = "https://login.sina.com.cn/sso/login.php"
        private const val LOGIN_ACCEPT: String = "text/javascript, application/javascript, */*; q=0.01"
        private const val LOGIN_DOCUMENT_ACCEPT: String =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private const val QR_LOGIN_ACCEPT: String = "application/json, text/plain, */*"
        private const val QR_LOGIN_RETURN_URL: String =
            "https://weibo.com/newlogin?tabtype=weibo&gid=102803&openLoginLayer=0&url=https://weibo.com/"
        private const val QR_LOGIN_SUCCESS_CODE: Long = 20_000_000
        private const val QR_LOGIN_WAITING_CODE: Long = 50_114_001
        private const val QR_LOGIN_SCANNED_CODE: Long = 50_114_002
        private const val QR_LOGIN_EXPIRED_CODE: Long = 50_114_003
        private const val QR_LOGIN_CANCELED_CODE: Long = 50_114_004
        private const val QR_LOGIN_SIZE: Int = 180
        private const val QR_LOGIN_POLL_INTERVAL_MILLIS: Long = 3_000
        private const val QR_LOGIN_TIMEOUT_MILLIS: Long = 180_000
        private const val DEFAULT_SSO_SAVE_STATE: Int = 30
        private const val MAX_QR_IMAGE_BYTES: Int = 1_024 * 1_024
        private const val QR_IMAGE_READ_BUFFER_SIZE: Int = 8 * 1_024
        private const val DESKTOP_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0"
        private const val UNKNOWN_WEIBO_USER_ID: String = "__unknown__"
        private const val UNKNOWN_WEIBO_USER_NAME: String = "未知微博用户"
        private const val WINDOW_CONFIG_MARKER: String = "window.\$CONFIG"
        private val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        private val QR_IMAGE_HOSTS: Set<String> = setOf(QR_IMAGE_HOST)
        private val SSO_META_REDIRECT_PATTERN: Regex = Regex(
            """location\.replace\(\s*(['\"])(.*?)\1\s*\)""",
            RegexOption.IGNORE_CASE,
        )
        private val WEIBO_JSON: Json = Json {
            ignoreUnknownKeys = true
        }
        private val WEIBO_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)

        private fun defaultHttpClient(cookie: String): HttpClient {
            val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            cookie.split(';')
                .mapNotNull { raw ->
                    raw.trim()
                        .takeIf(String::isNotBlank)
                        ?.let { runCatching { HttpCookie.parse(it).firstOrNull() }.getOrNull() }
                }
                .forEach { parsed ->
                    parsed.domain = ".weibo.com"
                    parsed.path = "/"
                    cookieManager.cookieStore.add(URI.create(WEIBO_HOME), parsed)
                }
            return HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
        }
    }
}

internal class WeiboHttpGateway(
    private val client: WeiboClient,
    private val requestIntervalMs: Long,
    private val followGroupName: String = "",
    private val autoCreateFollowGroup: Boolean = false,
) : WeiboGateway {
    private var cachedFriendTimelineListId: String? = null

    override fun exportCookie(): String {
        return client.exportCookieHeader()
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        return withRequestInterval {
            client.checkLoginState()
        }
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        return client.loginByQrCode(onQrCode, onStatusChanged)
    }

    override suspend fun refreshLoginSession(): PublisherLoginResult {
        return client.refreshLoginSession()
    }

    override suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot? {
        return withRequestInterval {
            client.fetchPublisherInfo(userId)
        }
    }

    override suspend fun fetchFollowTimeline(sinceEpochSeconds: Long?): WeiboTimelinePage {
        val listId = resolveFriendTimelineListId()
        if (sinceEpochSeconds == null) {
            return withRequestInterval {
                client.fetchFriendTimeline(
                    listId = listId,
                    sinceId = "0",
                )
            }
        }

        val posts = mutableListOf<WeiboPostSnapshot>()
        var nextCursor: String? = "0"
        var pageNumber = 1
        while (pageNumber <= HISTORY_REPLAY_MAX_PAGES) {
            val page = withRequestInterval {
                client.fetchFriendTimeline(
                    listId = listId,
                    sinceId = nextCursor.orEmpty().ifBlank { "0" },
                )
            }
            if (page.posts.isEmpty()) break

            posts += page.posts
            nextCursor = page.nextCursor
            if (page.posts.minOf { it.createdAtEpochSeconds } < sinceEpochSeconds) break
            if (nextCursor.isNullOrBlank()) break
            pageNumber += 1
        }
        return WeiboTimelinePage(
            posts = posts.distinctBy { it.postId },
            nextCursor = nextCursor,
        )
    }

    override suspend fun enrichPost(post: WeiboPostSnapshot): WeiboPostSnapshot {
        return post.enrichLongText()
    }

    private suspend fun resolveFriendTimelineListId(): String {
        cachedFriendTimelineListId?.takeIf { it.isNotBlank() }?.let { return it }
        val resolved = client.currentAccountFriendTimelineListId()
            ?: withRequestInterval { client.fetchDefaultFriendTimelineListId() }
        val listId = resolved?.takeIf { it.isNotBlank() }
            ?: throw WeiboApiException(
                "微博关注流请求缺少 list_id/fid：PC 首页未识别当前账号 UID，且微博分组接口未返回“最新微博”分组。",
            )
        cachedFriendTimelineListId = listId
        return listId
    }

    override suspend fun queryFollowState(userId: String): FollowState {
        return withRequestInterval {
            client.queryFollowState(userId)
        }
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        val result = withRequestInterval {
            client.followPublisher(userId)
        }
        if (result.status != FollowActionStatus.DONE && result.status != FollowActionStatus.NOOP) {
            return result
        }
        return result.withFollowGroupResult(assignConfiguredFollowGroup(userId))
    }

    override suspend fun unfollowPublisher(userId: String): FollowActionResult {
        val groupName = followGroupName.trim().takeIf { it.isNotBlank() }
            ?: return skipAutoUnfollow("未配置微博关注分组，无法确认是否为 Bot 自动关注")

        val assigned = runCatching {
            fetchAssignedFollowGroups(userId)
        }.getOrElse { error ->
            return skipAutoUnfollow("读取微博关注分组失败：${error.message ?: groupName}")
        }
        val userGroups = assigned.filter { it.id != DEFAULT_WEIBO_GROUP_ID }
        if (userGroups.isEmpty()) {
            return skipAutoUnfollow("目标用户不在配置分组：$groupName")
        }
        if (userGroups.size != 1 || userGroups.single().name != groupName) {
            return skipAutoUnfollow(
                "目标用户不只属于配置分组：configured=$groupName，actual=${userGroups.joinToString { it.name }}",
            )
        }

        val followState = runCatching {
            queryFollowState(userId)
        }.getOrDefault(FollowState.UNSUPPORTED)
        if (followState == FollowState.NOT_FOLLOWING) {
            return FollowActionResult(FollowActionStatus.NOOP, "当前账号未关注微博用户：$userId")
        }

        return withRequestInterval {
            client.unfollowPublisher(userId)
        }
    }

    override suspend fun fetchPostDetail(postId: String): WeiboPostSnapshot? {
        return withRequestInterval {
            client.fetchPostDetail(postId)?.enrichLongText()
        }
    }

    override suspend fun downloadVideoLink(request: LinkVideoDownloadRequest): LinkVideoDownloadResult {
        return withRequestInterval {
            client.downloadVideoLink(request)
        }
    }

    override suspend fun fetchAssignedFollowGroups(userId: String): List<WeiboFollowGroupSnapshot> {
        return withRequestInterval {
            client.fetchAssignedFollowGroups(userId)
        }
    }

    override suspend fun fetchAvailableFollowGroups(userId: String): List<WeiboFollowGroupSnapshot> {
        return withRequestInterval {
            client.fetchAvailableFollowGroups(userId)
        }
    }

    override suspend fun createFollowGroup(name: String, isPublic: Boolean): WeiboFollowGroupSnapshot? {
        return withRequestInterval {
            client.createFollowGroup(name, isPublic)
        }
    }

    override suspend fun setFollowGroups(
        userId: String,
        listIds: List<String>,
        originListIds: List<String>,
    ): Boolean {
        return withRequestInterval {
            client.setFollowGroups(userId, listIds, originListIds)
        }
    }

    private suspend fun assignConfiguredFollowGroup(userId: String): FollowGroupAssignResult? {
        val groupName = followGroupName.trim().takeIf { it.isNotBlank() } ?: return null
        val assigned = runCatching {
            fetchAssignedFollowGroups(userId)
        }.getOrElse { error ->
            return FollowGroupAssignResult(
                status = FollowActionStatus.NOOP,
                message = "关注成功，但读取微博关注分组失败：${error.message ?: groupName}",
            )
        }

        if (assigned.any { it.name == groupName }) {
            return FollowGroupAssignResult(
                status = FollowActionStatus.NOOP,
                message = "已在微博关注分组：$groupName",
            )
        }

        val available = runCatching {
            fetchAvailableFollowGroups(userId)
        }.getOrElse { error ->
            return FollowGroupAssignResult(
                status = FollowActionStatus.NOOP,
                message = "关注成功，但读取微博可选分组失败：${error.message ?: groupName}",
            )
        }

        var targetGroup = available.firstOrNull { it.name == groupName }
        if (targetGroup == null && autoCreateFollowGroup) {
            targetGroup = runCatching {
                createFollowGroup(groupName, isPublic = false)
            }.getOrNull()
        }
        if (targetGroup == null && autoCreateFollowGroup) {
            targetGroup = runCatching {
                fetchAvailableFollowGroups(userId).firstOrNull { it.name == groupName }
            }.getOrNull()
        }
        if (targetGroup == null) {
            val hint = if (autoCreateFollowGroup) "创建或查找失败" else "未启用自动创建"
            return FollowGroupAssignResult(
                status = FollowActionStatus.NOOP,
                message = "关注成功，但未加入微博关注分组：$groupName（$hint）",
            )
        }

        val originIds = assigned
            .map { it.id }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(DEFAULT_WEIBO_GROUP_ID) }
        val listIds = (originIds + targetGroup.id).distinct()
        val originListIds = listIds.map { id ->
            if (id == targetGroup.id && id !in originIds) DEFAULT_WEIBO_GROUP_ID else id
        }

        val updated = runCatching {
            setFollowGroups(
                userId = userId,
                listIds = listIds,
                originListIds = originListIds,
            )
        }.getOrDefault(false)

        return if (updated) {
            FollowGroupAssignResult(
                status = FollowActionStatus.DONE,
                message = "已加入微博关注分组：$groupName",
            )
        } else {
            FollowGroupAssignResult(
                status = FollowActionStatus.NOOP,
                message = "关注成功，但加入微博关注分组失败：$groupName",
            )
        }
    }

    private fun FollowActionResult.withFollowGroupResult(groupResult: FollowGroupAssignResult?): FollowActionResult {
        if (groupResult == null) return this
        return copy(
            status = if (groupResult.status == FollowActionStatus.DONE) FollowActionStatus.DONE else status,
            message = listOfNotNull(message, groupResult.message)
                .filter { it.isNotBlank() }
                .joinToString("；")
                .takeIf { it.isNotBlank() },
        )
    }

    private fun skipAutoUnfollow(reason: String): FollowActionResult {
        return FollowActionResult(
            FollowActionStatus.NOOP,
            "已跳过微博自动取关：$reason",
        )
    }

    private suspend fun WeiboPostSnapshot.enrichLongText(): WeiboPostSnapshot {
        val updated = if (isLongText) {
            val longText = withRequestInterval { client.fetchLongText(postId) }
            if (longText == null) {
                this
            } else {
                copy(
                    text = longText.content,
                    rawText = longText.rawContent,
                    isLongText = false,
                )
            }
        } else {
            this
        }
        return updated.copy(
            reposted = updated.reposted?.enrichLongText(),
        )
    }

    private suspend fun <T> withRequestInterval(block: suspend () -> T): T {
        return try {
            block()
        } finally {
            if (requestIntervalMs > 0) {
                delay(requestIntervalMs)
            }
        }
    }

    private companion object {
        private const val HISTORY_REPLAY_MAX_PAGES: Int = 3
    }
}

private data class FollowGroupAssignResult(
    val status: FollowActionStatus,
    val message: String,
)

private const val WEIBO_HOME: String = "https://weibo.com"
private const val DEFAULT_WEIBO_GROUP_ID: String = "0"
