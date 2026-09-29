package com.cs2stats.app.data.collect

import android.content.Context
import android.webkit.CookieManager
import com.cs2stats.app.data.model.MatchDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Steam 官匹比赛采集：用登录态拉「我的游戏数据」(gcpd) 的比赛记录。
 *
 * ## 走这条路的原因（2026-09 逐个实测）
 *
 * | 路径 | 结论 |
 * | --- | --- |
 * | `ICSGOApps_1007/GetMatchHistory`（老官方 API） | **已下线**：`Interface 'ICSGOApps_1007' not found` |
 * | `ICSGOServers_730` | 只剩 `GetGameMapsPlaytime` / `GetGameServersStatus`，无战绩 |
 * | `ICSGOPlayers_730/GetNextMatchSharingCode` | 唯一存活的战绩相关方法（SteamDatabase 转储确认），要**游戏认证码**，且只返回**分享码**不含 K/D |
 * | 解析 CS2 demo | 需下载数十 MB demo 并解析，手机端不现实 |
 * | 社区页 `gcpd/730` | **可行**：未登录会 302 回首页/资料页（实测 `g_steamID = false`），带登录 Cookie 即可 |
 *
 * ## 协议（依据 Leetify 开源扩展 `leetify-gcpd-upload`，已在真机校准）
 *
 * - `GET /my/gcpd/730[?ajax=1&tab=<tab>&continue_token=...]` → ajax 返回
 *   JSON `{html, continue_token, continue_text}`，**分页拉取**；完整页面返回 HTML（同样能解析）；
 * - 会话约一天过期，跳到 `/login/` 时用 `https://login.steampowered.com/jwt/refresh?redir=<原地址>` 续期；
 * - 标签页：`matchhistorypremier` / `matchhistorycompetitive` / `matchhistorywingman` /
 *   `matchhistoryscrimmage` / `matchhistoryrush` / `matchhistorycasual`。
 *   **注意 `tab=matchhistory` 不是比赛列表**（返回的是账号活动日志，实测确认）；
 * - HTML 结构与字段含义见 [GcpdParser]。
 *
 * 每次响应都会落到 `files/collector/`（按页存档、不互相覆盖），登录失败时也照存，
 * 便于离线校准选择器。
 */
class SteamMatchCollector(private val context: Context) {

    data class FetchResult(
        val tab: String,
        val matches: List<MatchDetail>,
        val pages: Int,
        val bytes: Int,
        val diagnosticFile: File?,
        val note: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val diagnosticDir: File
        get() = File(context.filesDir, "collector").apply { mkdirs() }

    // ---------- 对外 ----------

    /**
     * 依次抓取各模式的比赛记录；标签页之间互不影响（单页失败会跳过并记录），
     * 但登录态失效会立刻整体中断。
     */
    suspend fun collect(mySteamId: String, myName: String): List<FetchResult> =
        withContext(Dispatchers.IO) {
            val results = ArrayList<FetchResult>(TABS.size)
            var lastError: IOException? = null
            for (tab in TABS) {
                try {
                    results += fetchTab(tab, mySteamId, myName)
                } catch (e: IOException) {
                    val auth = e.message?.let {
                        it.contains("登录") || it.contains("重定向") || it.contains("Cookie")
                    } == true
                    if (auth) throw e
                    lastError = e
                }
            }
            if (results.isEmpty()) throw (lastError ?: IOException("没有抓到任何标签页。"))
            results
        }

    fun diagnostics(): List<File> =
        diagnosticDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    // ---------- 抓取 ----------

    private fun fetchTab(tab: String, mySteamId: String, myName: String): FetchResult {
        if (!SteamWebSession.isLoggedIn()) {
            throw IOException("没有 Steam 登录态：先完成「Steam 登录（应用内授权）」。")
        }

        val found = LinkedHashMap<String, MatchDetail>() // id -> match，天然跨页/跨标签去重
        var pages = 0
        var bytes = 0
        var emptyStreak = 0
        var lastContinueText: String? = null

        fun absorb(html: String) {
            val parsed = GcpdParser.parse(html, tab, mySteamId, myName)
            parsed.matches.forEach { found.putIfAbsent(it.id, it) }
        }

        // ① 完整页面：结构最全，也是 ajax 出问题时的兜底
        val fullUrl = "$BASE/?tab=$tab"
        runCatching { getFollowingRedirects(fullUrl, fullUrl) }.getOrNull()?.let { page ->
            requireGcpd(page.finalUrl)
            saveDiagnostic(tab, page.body, "_full", "html")
            bytes += page.body.length
            pages++
            absorb(page.body)
        }

        // ② ajax 分页（历史记录向更早追溯）
        var token: String? = null
        while (pages < MAX_PAGES + 1) {
            val url = buildString {
                append(BASE).append("?ajax=1&tab=").append(tab)
                token?.let { append("&continue_token=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            }
            val page = getFollowingRedirects(url, originalForRefresh = url)
            requireGcpd(page.finalUrl)
            pages++
            saveDiagnostic(tab, page.body, "_p$pages")
            bytes += page.body.length

            val json = runCatching { JSONObject(page.body) }
                .getOrElse { throw IOException("gcpd 返回的不是 JSON（可能是登录页或被限流）。") }

            val html = json.optString("html").orEmpty()
            absorb(html)

            // 连续多页没有比赛格 = 翻到头（Steam 偶尔会先吐空页再继续，故容忍 2 页）
            emptyStreak = if (html.contains("val_left")) 0 else emptyStreak + 1

            lastContinueText = json.optString("continue_text")
                .takeIf { !it.isNullOrBlank() && it != "null" }
            token = json.optString("continue_token").takeIf { !it.isNullOrBlank() && it != "null" }

            if (token == null || emptyStreak >= 3) break
            Thread.sleep(PAGE_DELAY_MS) // 别打太密，避免被限流
        }

        val matches = found.values.sortedByDescending { it.startedAt }
        val range = when {
            matches.isEmpty() -> "无记录"
            matches.size == 1 -> fmtDate(matches.first().startedAt)
            else -> "${fmtDate(matches.last().startedAt)} ~ ${fmtDate(matches.first().startedAt)}"
        }
        val note = buildString {
            append("${matches.size} 场 · $pages 页 · $range")
            if (lastContinueText != null) append(" · 追溯到 $lastContinueText")
            val noDemo = matches.count { !it.id.startsWith("gcpd-dem") && it.id.startsWith("gcpd-") }
            if (noDemo > 0 && matches.isNotEmpty()) {
                append("（$noDemo 场已超 Valve 30 天 demo 保留期，按开赛时间做主键）")
            }
        }

        return FetchResult(
            tab = tab,
            matches = matches,
            pages = pages,
            bytes = bytes,
            diagnosticFile = File(diagnosticDir, "gcpd_${tab}_p1.json"),
            note = note,
        )
    }

    private fun requireGcpd(finalUrl: String) {
        if (finalUrl.contains("steamcommunity.com/login") || !finalUrl.contains("/gcpd/730")) {
            throw IOException(
                "被重定向到 $finalUrl：登录态无效或已过期，请重新登录 Steam。（本次响应已存档）"
            )
        }
    }

    /** 一次抓取的最终落点与响应体；校验、存档都在调用方做，保证失败也留下样本。 */
    private data class Page(val finalUrl: String, val body: String)

    /** 把原始响应存到应用私有目录，供离线校准选择器（失败路径也要存）。 */
    private fun saveDiagnostic(tab: String, body: String, suffix: String = "", ext: String = "json") {
        if (body.isBlank()) return
        runCatching { File(diagnosticDir, "gcpd_$tab$suffix.$ext").writeText(body) }
    }

    /**
     * 带 Cookie 手动跟随重定向：这样每跳的 `Set-Cookie` 都能写回 WebView 会话，
     * 两端登录态保持同步（[SteamWebSession] 是共享的）。
     *
     * @throws IOException 登录态失效 / 网络异常 / 重定向过多
     */
    private fun getFollowingRedirects(startUrl: String, originalForRefresh: String): Page {
        var url = startUrl
        var refreshed = false

        repeat(MAX_REDIRECTS) {
            val req = Request.Builder()
                .url(url)
                .header("Cookie", SteamWebSession.cookieHeader(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://steamcommunity.com/")
                .build()

            http.newCall(req).execute().use { resp ->
                // 把 Steam 回写的 Cookie 同步进 WebView 会话
                if (resp.headers("Set-Cookie").isNotEmpty()) {
                    resp.headers("Set-Cookie").forEach { c ->
                        CookieManager.getInstance().setCookie(url, c)
                    }
                    CookieManager.getInstance().flush()
                }

                when {
                    resp.code in 300..399 -> {
                        val location = resp.header("Location")
                            ?: throw IOException("Steam 重定向缺少 Location（HTTP ${resp.code}）")
                        url = resolve(url, location)
                    }

                    resp.code == 200 -> {
                        val body = resp.body?.string().orEmpty()
                        // 跳到了登录页 → 走官方会话续期端点再回来
                        if (url.contains("steamcommunity.com/login") && !refreshed) {
                            refreshed = true
                            url = "https://login.steampowered.com/jwt/refresh?redir=" +
                                java.net.URLEncoder.encode(originalForRefresh, "UTF-8")
                            return@repeat
                        }
                        if (body.isBlank()) throw IOException("gcpd 返回了空响应。")
                        // 最终落点与响应体交回调用方：先存样本再判有效，失败也不丢证据
                        return Page(finalUrl = url, body = body)
                    }

                    resp.code == 401 || resp.code == 403 ->
                        throw IOException("Steam 拒绝访问（HTTP ${resp.code}）。")

                    else -> throw IOException("Steam 返回 HTTP ${resp.code}。")
                }
            }
        }
        throw IOException("重定向次数过多，可能是会话异常。")
    }

    private fun resolve(from: String, location: String): String =
        from.toHttpUrl().resolve(location)?.toString()
            ?: throw IOException("无法解析重定向地址：$location")

    private fun fmtDate(epochSec: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(epochSec * 1000))

    companion object {
        /**
         * CS2 官匹各模式。**没有 `matchhistory`**——它返回的是账号活动日志而非比赛列表（实测）；
         * `matchhistorycasual` 只有「时间/模式」清单、`deepplayerstatsmatchentry` 没有记分板，
         * 两者都拿不到比赛行，故不抓（样本与结论已固化在 GcpdSamplesTest 里）。
         */
        private val TABS = listOf(
            "matchhistorypremier",
            "matchhistorycompetitive",
            "matchhistorywingman",
            "matchhistoryscrimmage",
            "matchhistoryrush",
        )

        private const val BASE = "https://steamcommunity.com/my/gcpd/730"
        private const val MAX_PAGES = 16
        private const val MAX_REDIRECTS = 8
        private const val PAGE_DELAY_MS = 250L

        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
