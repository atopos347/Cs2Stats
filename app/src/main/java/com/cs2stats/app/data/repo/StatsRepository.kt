package com.cs2stats.app.data.repo

import com.cs2stats.app.data.local.AppSettings
import com.cs2stats.app.data.local.SettingsStore
import com.cs2stats.app.data.mock.MockData
import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.MatchResult
import com.cs2stats.app.data.model.OverallStats
import com.cs2stats.app.data.model.PlayerLine
import com.cs2stats.app.data.model.SteamProfile
import com.cs2stats.app.data.rating.Rating3
import com.cs2stats.app.data.rating.mean
import com.cs2stats.app.data.remote.ApiClient
import com.cs2stats.app.data.store.SyncStore
import com.cs2stats.app.data.store.WebDavStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** 一次数据加载的结果。 */
data class LoadResult(
    val profile: SteamProfile?,
    val matches: List<MatchDetail>,
    val sourceLabel: String,
    val error: String? = null,
    /** true = 这批比赛是**示例数据**（种子生成，日期贴近“今天”），不能与真实采集结果混算。 */
    val isSample: Boolean = false,
)

/**
 * 数据仓库：按「数据空间」设置分发。
 *
 * ```
 * 示例数据  ──────────────────────────────► MockData（离线跑界面与算法）
 * 网盘 WebDAV ─ 读 profile/matches.json ◄─ 写   （坚果云等，当前默认）
 * 自建后端   ─ GET/POST /api/v1/...               （保留备用）
 * ```
 *
 * Rating 3.0 始终在**本地**计算，网盘/服务器只负责存取原始 JSON。
 */
class StatsRepository(
    private val settingsStore: SettingsStore,
    /** 应用私有目录：本地采集缓存写在这里（不传则不落盘，仅内存态）。 */
    private val filesDir: File? = null,
) {

    val settings: Flow<AppSettings> = settingsStore.settings

    // ---------- 本地采集缓存 ----------

    private val localMatchesFile: File?
        get() = filesDir?.let { File(it, "local_matches.json") }

    /**
     * 手机抓到的比赛先落本地缓存：重启、断网、网盘没配时都能看到，
     * 再由「上传到网盘」完成归档。**示例数据永远不进缓存。**
     */
    suspend fun saveLocalMatches(matches: List<MatchDetail>) {
        val f = localMatchesFile ?: return
        if (matches.isEmpty()) return
        withContext(Dispatchers.IO) { runCatching { f.writeText(ApiClient.matchesToJson(matches)) } }
    }

    fun loadLocalMatches(): List<MatchDetail> {
        val f = localMatchesFile ?: return emptyList()
        return runCatching {
            if (f.exists()) ApiClient.parseMatches(f.readText()) else emptyList()
        }.getOrDefault(emptyList())
    }

    /** 本地缓存与数据空间合并（按比赛主键去重，时间倒序）。 */
    private fun union(local: List<MatchDetail>, remote: List<MatchDetail>): List<MatchDetail> =
        (local + remote).distinctBy { it.id }.sortedByDescending { it.startedAt }

    /**
     * 把本地采集缓存并入加载结果。
     *
     * **关键**：如果底座是示例数据（`isSample`），则整体**替换**而不是相加——
     * 否则会出现“今天没打过炼狱小镇，列表里却有”的假数据（示例数据的日期就是今天）。
     */
    private fun mergeLocal(base: LoadResult, local: List<MatchDetail>): LoadResult {
        if (local.isEmpty()) return base
        return if (base.isSample) {
            base.copy(
                matches = local,
                sourceLabel = "本地采集 ${local.size} 场（示例数据已丢弃）",
                isSample = false,
            )
        } else {
            base.copy(
                matches = union(local, base.matches),
                sourceLabel = "${base.sourceLabel} · 已并入本地 ${local.size} 场",
            )
        }
    }

    /** 修改设置（DataStore 变化会触发上层重新加载）。 */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) =
        settingsStore.update(transform)

    // ---------- 读 ----------

    suspend fun load(s: AppSettings): LoadResult {
        // 显式选了示例数据就原样返回，不掺真实采集结果
        if (s.useMockData) return mock(s)

        val local = withContext(Dispatchers.IO) { loadLocalMatches() }
        val base = when {
            s.storageMode == AppSettings.MODE_WEBDAV ->
                if (s.hasWebDav) loadWebDav(s) else LoadResult(
                    profile = profileFallback(s),
                    matches = MockData.matches(),

                    sourceLabel = s.sourceLabel,
                    error = "网盘配置不完整：需要地址、账号、应用密码三项。",
                    isSample = true,
                )
            s.hasServer -> loadServer(s)
            else -> LoadResult(
                profile = profileFallback(s),
                matches = MockData.matches(),

                sourceLabel = "未配置数据空间",
                // 说清「优先级」而不是写死结论：下面 mergeLocal 会用本地采集缓存顶掉
                // 示例数据，届时再说「当前显示示例数据」就是假话了（真机踩过）。
                error = "数据空间未配置完整：优先显示本地采集缓存，没有缓存时才显示示例数据。",
                isSample = true,
            )
        }
        return mergeLocal(base, local)
    }

    private fun mock(s: AppSettings) = LoadResult(
        profile = profileFallback(s) ?: MockData.profile(),
        matches = MockData.matches(),

        sourceLabel = "本地示例数据（固定种子生成）",
        isSample = true,
    )

    private fun profileFallback(s: AppSettings): SteamProfile? =
        if (s.isSteamLoggedIn) {
            SteamProfile(
                steamId = s.steamId,
                personaName = s.steamName.ifBlank { "你" },
                avatarUrl = s.avatarUrl,
            )
        } else null

    private suspend fun loadWebDav(s: AppSettings): LoadResult {
        val store = storeFor(s) ?: return mock(s)
        return try {
            val profileJson = store.read(SyncStore.PROFILE)
            val matchesJson = store.read(SyncStore.MATCHES)

            val matches = matchesJson?.let { ApiClient.parseMatches(it) } ?: emptyList()
            val profile = profileJson?.let { ApiClient.parseProfile(it) }
                ?: profileFallback(s) ?: MockData.profile()

            LoadResult(
                profile = profile,
                matches = matches,
                sourceLabel = "网盘 ${store.label}",
                error = if (matchesJson == null) {
                    "网盘上还没有 matches.json：先在设置里点「上传到网盘」，或等待比赛采集接入。"
                } else null,
            )
        } catch (e: Exception) {
            LoadResult(
                profile = profileFallback(s),
                matches = MockData.matches(),

                sourceLabel = "网盘连接失败，已回退到示例数据",
                error = e.message ?: e.javaClass.simpleName,
                isSample = true,
            )
        }
    }

    private suspend fun loadServer(s: AppSettings): LoadResult = try {
        val api = ApiClient(s.serverBaseUrl, s.apiToken)
        val matches = api.matches()
        val profile = runCatching { api.profile() }.getOrNull()
            ?: profileFallback(s)
        LoadResult(
            profile = profile,
            matches = matches,
            sourceLabel = "服务器 ${s.serverBaseUrl}",
            error = if (matches.isEmpty()) "服务器返回 0 场比赛" else null,
        )
    } catch (e: Exception) {
        LoadResult(
            profile = profileFallback(s),
            matches = MockData.matches(),

            sourceLabel = "连接失败，已回退到示例数据",
            error = e.message ?: e.javaClass.simpleName,
            isSample = true,
        )
    }

    // ---------- 写 ----------

    /**
     * 把当前数据集写入网盘（手机检测到新比赛后的「归档 → 上传」环节）。
     * 返回一句成功说明，失败抛异常给调用方提示。
     */
    suspend fun uploadToCloud(
        s: AppSettings,
        profile: SteamProfile?,
        matches: List<MatchDetail>,
    ): String {
        val store = storeFor(s)
            ?: throw IllegalStateException("网盘未配置：先填好地址、账号与应用密码。")
        if (matches.isEmpty()) {
            throw IllegalStateException("没有可上传的数据。")
        }
        profile?.let { store.write(SyncStore.PROFILE, ApiClient.profileToJson(it)) }
        store.write(SyncStore.MATCHES, ApiClient.matchesToJson(matches))
        return "已上传：${matches.size} 场比赛 → ${store.label}"
    }

    /** 连通性与凭据自测。 */
    suspend fun testCloud(s: AppSettings): Boolean {
        val store = storeFor(s) ?: return false
        return store.test()
    }

    private fun storeFor(s: AppSettings): SyncStore? =
        if (s.hasWebDav) WebDavStore(s.davUrl, s.davUser, s.davPass) else null

    // ---------- 聚合 ----------

    companion object {

        /** 总览聚合：总场均 Rating / 总爆头率 / K-D / KAST / ADR / 胜率。 */
        fun buildOverall(
            matches: List<MatchDetail>,
            mySteamId: String,
        ): OverallStats {
            if (matches.isEmpty()) {
                return OverallStats()
            }

            val myLines: List<PlayerLine> = matches.mapNotNull { it.myLine(mySteamId) }
            if (myLines.isEmpty()) {
                return OverallStats(
                    matchCount = matches.size,
                    winCount = matches.count { it.result == MatchResult.WIN },
                )
            }

            val ratings = myLines.map { Rating3.ratingOf(it) }

            // 总爆头率 = 总爆头击杀 / 总击杀（而非单场百分比的平均）
            val totalKills = myLines.sumOf { it.kills }
            val totalHs = myLines.sumOf { it.headshotKills }
            val totalDeaths = myLines.sumOf { it.deaths }

            val kastLines = myLines.filter { it.kastRounds != null && it.roundsPlayed > 0 }
            val kastRate = if (kastLines.isNotEmpty()) {
                kastLines.sumOf { it.kastRounds!! }.toDouble() / kastLines.sumOf { it.roundsPlayed }
            } else 0.0

            val damageLines = myLines.filter { it.damage > 0 && it.roundsPlayed > 0 }

            return OverallStats(
                matchCount = matches.size,
                winCount = matches.count { it.result == MatchResult.WIN },
                avgRating = ratings.mean(),
                avgHeadshotRate = if (totalKills > 0) totalHs.toDouble() / totalKills else 0.0,
                kdRatio = if (totalDeaths > 0) totalKills.toDouble() / totalDeaths else totalKills.toDouble(),
                kastRate = kastRate,
                avgAdr = damageLines.map { it.adr }.mean(),
                avgKpr = myLines.map { it.kpr }.mean(),
            )
        }

        /** 单场明细：我这一行。 */
        fun myLineOf(match: MatchDetail, mySteamId: String): PlayerLine? = match.myLine(mySteamId)
    }
}
