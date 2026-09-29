package com.cs2stats.app.data.collect

import android.webkit.CookieManager

/**
 * Steam 登录态桥接。
 *
 * CookieManager 是**进程级单例**：Steam OpenID 登录写进 WebView 的 Cookie，
 * 这里原样取出来给 OkHttp 用，采集请求因此共享同一份登录会话，
 * 不需要再单独存 token、也不用碰用户密码。
 *
 * 关键 Cookie：`steamLoginSecure`（登录凭证，HttpOnly，只能通过 CookieManager 读）。
 */
object SteamWebSession {

    private const val COMMUNITY = "https://steamcommunity.com"

    /** WebView 里是否已有 Steam 登录态。 */
    fun isLoggedIn(): Boolean =
        CookieManager.getInstance().getCookie(COMMUNITY)
            ?.contains("steamLoginSecure=") == true

    /** 取某个 URL 上生效的 Cookie 头（含域内全部 Cookie）。 */
    fun cookieHeader(url: String): String =
        CookieManager.getInstance().getCookie(url) ?: ""

    /** 退出登录时同时清掉采集会话。 */
    fun clear() {
        val m = CookieManager.getInstance()
        m.removeAllCookies(null)
        m.flush()
    }
}
