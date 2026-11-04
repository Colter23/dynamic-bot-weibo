package top.colter.dynamic.weibo

internal enum class WeiboSessionFailure(val description: String, val needsUser: Boolean = false) {
    MISSING_CREDENTIAL("缺少 ALC 等长期续期凭据，请重新扫码或导入完整 Cookie JSON", true),
    LOGIN_REQUIRED("长期登录凭据已失效，请重新扫码登录", true),
    CHALLENGE("微博要求设备指纹或验证码验证，请在浏览器处理后导入完整 Cookie JSON，或重新扫码", true),
    ACCOUNT_CHANGED("续期账号与原账号不一致，请重新登录确认账号", true),
    PROTOCOL("微博登录响应或跳转不受支持，请重新登录", true),
    TRANSIENT("微博连接暂时失败，将稍后重试"),
}

internal class WeiboSessionException(val failure: WeiboSessionFailure) : WeiboApiException(failure.description)
