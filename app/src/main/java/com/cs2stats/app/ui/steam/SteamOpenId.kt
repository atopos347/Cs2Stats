package com.cs2stats.app.ui.steam

import android.net.Uri

/**
 * Steam OpenID 2.0 授权参数。
 *
 * 授权完成后 Steam 会 302 到 [RETURN_TO]，并在 query 里带上
 * `openid.claimed_id=https://steamcommunity.com/openid/id/<SteamID64>`，
 * 由 WebView 的 shouldOverrideUrlLoading 拦截解析（无需自己的公网回调服务器）。
 */
object SteamOpenId {

    /** 本地占位回调地址：不会真的被访问，只用来承接 Steam 拼接的 query。 */
    const val RETURN_TO = "https://cs2stats.local/steam/callback"
    const val REALM = "https://cs2stats.local"

    private const val LOGIN = "https://steamcommunity.com/openid/login"

    fun authUrl(): String = buildString {
        append(LOGIN)
        append("?openid.ns=").append(Uri.encode("http://specs.openid.net/auth/2.0"))
        append("&openid.mode=checkid_setup")
        append("&openid.identity=").append(Uri.encode("http://specs.openid.net/auth/2.0/identifier_select"))
        append("&openid.claimed_id=").append(Uri.encode("http://specs.openid.net/auth/2.0/identifier_select"))
        append("&openid.return_to=").append(Uri.encode(RETURN_TO))
        append("&openid.realm=").append(Uri.encode(REALM))
    }

    /**
     * 从回调 URL 中取 SteamID64。
     * - 返回 17 位数字：授权成功；
     * - 返回 null 且 [isCallback] 为 true：用户点了取消。
     */
    fun parse(url: String): Pair<String?, Boolean> {
        if (!url.startsWith(RETURN_TO)) return null to false
        val claimed = Uri.parse(url).getQueryParameter("openid.claimed_id")
        val id = claimed?.substringAfterLast('/')?.takeIf { it.length == 17 && it.all { c -> c.isDigit() } }
        return id to true
    }
}
