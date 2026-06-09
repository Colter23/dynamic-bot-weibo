package top.colter.dynamic.weibo

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigNumberKind

public data class WeiboPublisherConfig(
    val pollingEnabled: Boolean = false,
    val pollingIntervalSeconds: Double = 60.0,
    val requestIntervalSeconds: Double = 1.0,
    val maxPollPages: Int = 3,
    val useFriendTimelinePolling: Boolean = true,
    val replayWindowMinutes: Int = 0,
    val followGroupName: String = "",
    val autoCreateFollowGroup: Boolean = false,
    val maxConsecutiveLoginFailures: Int = 3,
    val loginUserId: String = "",
    val cookie: String = "",
)

public object WeiboPublisherConfigForm {
    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "微博动态源",
        description = "微博动态轮询、关注分组、登录状态与请求配置。",
        fields = listOf(
            ConfigFieldSpec(
                path = "pollingEnabled",
                label = "启用轮询",
                type = ConfigFieldType.BOOLEAN,
                section = "轮询",
                description = "开启后按配置间隔检测已订阅微博用户的新动态；关闭时插件仍可用于资料查询、关注和链接解析。",
                restartRequired = true,
                restartTarget = "微博插件",
            ),
            ConfigFieldSpec(
                path = "pollingIntervalSeconds",
                label = "轮询间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询",
                description = "多久检查一次已订阅微博用户的新动态。",
                min = 5,
                restartRequired = true,
                restartTarget = "微博插件",
            ),
            ConfigFieldSpec(
                path = "requestIntervalSeconds",
                label = "请求间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "请求",
                description = "连续请求微博接口之间等待多久，用于降低触发风控的概率。",
                min = 0,
                restartRequired = true,
                restartTarget = "微博插件",
            ),
            ConfigFieldSpec(
                path = "maxPollPages",
                label = "每轮最多翻页数",
                type = ConfigFieldType.NUMBER,
                section = "轮询",
                description = "关注流或逐用户时间线每轮最多向后翻几页。调大可以降低漏消息概率，但会增加请求量。",
                min = 1,
                numberKind = ConfigNumberKind.INTEGER,
            ),
            ConfigFieldSpec(
                path = "useFriendTimelinePolling",
                label = "优先使用关注流",
                type = ConfigFieldType.BOOLEAN,
                section = "轮询",
                description = "开启后只从当前账号关注流拉取动态并按订阅 UID 分发，以减少请求量；关闭后才逐用户拉取时间线。",
                restartRequired = true,
                restartTarget = "微博插件",
            ),
            ConfigFieldSpec(
                path = "replayWindowMinutes",
                label = "补发时间窗口（分钟）",
                type = ConfigFieldType.NUMBER,
                section = "补发",
                description = "启动后补发最近一段时间的新微博；设为 0 时只记录当前位置，避免首次推送旧内容。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
            ),
            ConfigFieldSpec(
                path = "followGroupName",
                label = "关注分组名",
                type = ConfigFieldType.TEXT,
                section = "关注",
                description = "关注微博用户后自动加入这个分组；留空时不处理关注分组。",
            ),
            ConfigFieldSpec(
                path = "autoCreateFollowGroup",
                label = "自动创建关注分组",
                type = ConfigFieldType.BOOLEAN,
                section = "关注",
                description = "开启后，如果关注分组名不存在，会先创建分组再加入用户；关闭时仅加入已有分组。",
            ),
            ConfigFieldSpec(
                path = "maxConsecutiveLoginFailures",
                label = "未登录暂停阈值",
                type = ConfigFieldType.NUMBER,
                section = "异常处理",
                description = "连续几次检测到未登录后暂停轮询。设为 0 表示不自动暂停；更新 Cookie 或登录恢复后会继续。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
            ),
            ConfigFieldSpec(
                path = "loginUserId",
                label = "登录账号 UID",
                type = ConfigFieldType.TEXT,
                section = "请求",
                description = "当前登录微博账号 UID。优先使用关注流时用于构造 list_id/fid；留空时会尝试从微博分组接口获取默认关注流分组。",
            ),
            ConfigFieldSpec(
                path = "cookie",
                label = "微博 Cookie",
                type = ConfigFieldType.SECRET,
                section = "请求",
                description = "需要登录态的接口会使用此 Cookie。可从浏览器复制 Cookie 请求头。",
                secret = true,
                restartRequired = true,
                restartTarget = "微博插件",
            ),
        ),
    )

    public fun validate(config: WeiboPublisherConfig) {
        require(config.pollingIntervalSeconds >= 5.0) { "微博轮询间隔不能小于 5 秒" }
        require(config.requestIntervalSeconds >= 0.0) { "微博请求间隔不能为负数" }
        require(config.maxPollPages >= 1) { "微博每轮最多翻页数不能小于 1" }
        require(config.replayWindowMinutes >= 0) { "微博补发时间窗口不能为负数" }
        require(config.maxConsecutiveLoginFailures >= 0) { "微博未登录暂停阈值不能为负数" }
    }
}
