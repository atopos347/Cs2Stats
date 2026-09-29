package com.cs2stats.app.data.collect

import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.PlayerLine
import com.cs2stats.app.data.model.Team
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 解析 Steam「我的游戏数据」gcpd 比赛记录 HTML。
 *
 * 页面结构（2026-09-28 用真实登录态样本校准，样本存于 `files/collector/`）：
 *
 * ```html
 * <table class="generic_kv_table csgo_scoreboard_root">
 *   <tr><th class="col_left">Map</th><th>Match Results</th></tr>
 *   <tr>
 *     <td class="val_left">                     <!-- 一场比赛 = 一个这样的格子 -->
 *       <img …/>                                 <!-- 地图缩略图 -->
 *       <table class="csgo_scoreboard_inner_left">
 *         <tr><td>Premier Ancient</td></tr>       <!-- 模式 + 地图 -->
 *         <tr><td>2026-09-28 08:52:25 GMT</td></tr>
 *         <tr><td>Ranked: Yes</td></tr>
 *         <tr><td>Wait Time: 01:15</td></tr>
 *         <tr><td>Match Duration: 38:03</td></tr>
 *         <tr><td class="csgo_scoreboard_cell_noborder">
 *           <a href="http://replay403.valve.net/730/<demoId>.dem.bz2">…</a></td>
 *       </table>
 *     </td>
 *     <td>
 *       <table class="csgo_scoreboard_inner_right">
 *         <tr><th class="inner_name">Player Name</th><th>Ping</th><th>K</th><th>A</th>
 *             <th>D</th><th>★图标</th><th>HSP</th><th>Score</th></tr>
 *         … 5 行（第 0 组）…
 *         <tr><td colspan="8" class="csgo_scoreboard_score">13 : 11</td></tr>
 *         … 5 行（第 1 组）…
 *       </table>
 *     </td>
 *   </tr>
 * </table>
 * ```
 *
 * ## 由这些字段能确定得到的
 *
 * 地图、模式、开赛时间、时长、**双方比分**、**10 人 K/A/D、HSP（爆头率%）**、
 * **Ping、★MVP 星数、Score 列**、`Ranked: Yes/No`、`Wait Time`、demo 链接（稳定主键）。
 *
 * ### 记分板列序会变，不能写死下标
 *
 * `Ping K A D ★ HSP Score` 是标准列序，但真实样本里 ★ 列有两种缺失写法：
 * 整列省略（`32 | 8 | 2 | 8 | 12% | 17`）或写成 `&nbsp;`（`56 | 4 | 3 | 9 |   | 75% | 9`），
 * Ping 也可能整格为 `&nbsp;`。因此解析时先找 ★ / % / 空格**边界**，K/A/D 三列紧贴其前。
 *
 * ## 胜负的判定依据
 *
 * 比分**未按胜负排序**（样本里 `13:5` 与 `5:13` 同时存在），且比分行夹在两组球员行之间，
 * 因此 `比分 = 第0组 : 第1组`，我的组的回合数即 [MatchDetail.myScore]。
 *
 * ## 官匹页面**没有**的字段（Rating 会自动降级，绝不编造）
 *
 * 伤害/ADR、KAST、存活回合、多杀回合、开局杀、回合经济 → 对应子项缺席，
 * 由 [com.cs2stats.app.data.rating.Rating3] 从权重中剔除并重新归一化。
 *
 * 唯一的例外是**存活回合**：它不是估算而是恒等式（每回合最多阵亡一次 →
 * `存活 = 回合 − 阵亡`），由 [PlayerLine.survivedRoundsEff] 反推并标注 `≈`。
 *
 * 已实测排除的取数路径：`deepplayerstatsmatchentry` / `deepplayerstatsmatchevent`
 * 两个页签（Match Stats / Match Events）ajax 返回 `html` 为空——Valve 不提供逐回合数据。
 */
object GcpdParser {

    /** 一次解析的结果：比赛 + 未能识别的比赛块数量（用于诊断提示）。 */
    data class Result(
        val matches: List<MatchDetail>,
        val blocks: Int,
        val unparseable: Int,
    )

    private val cellSplit = Regex("""<td[^>]*class="[^"]*\bval_left\b[^"]*"""")
    private val leftTable =
        Regex("""<table class="csgo_scoreboard_inner_left">(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
    private val rightTable =
        Regex("""<table class="csgo_scoreboard_inner_right">(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
    /** 无属性的 `<td>`：正好对应记分板的数据列（`inner_name` 列带 class，故被排除）。 */
    private val plainTd = Regex("""<td>\s*([^<]*?)\s*</td>""")
    private val scoreTd =
        Regex("""<td[^>]*class="csgo_scoreboard_score"[^>]*>\s*(\d+)\s*:\s*(\d+)\s*</td>""")
    private val demoRe = Regex("""replay\d+\.valve\.net/730/(\d+_\d+)\.dem\.bz2""")
    private val replayUrlRe = Regex("""https?://replay\d+\.valve\.net/730/\d+_\d+\.dem\.bz2""")
    private val dateRe = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    private val durationRe = Regex("""Match Duration:\s*(\d+):(\d+)(?::(\d+))?""")
    private val waitTimeRe = Regex("""Wait Time:\s*(\d+):(\d+)(?::(\d+))?""")
    private val rankedRe = Regex("""Ranked:\s*(Yes|No)""", RegexOption.IGNORE_CASE)
    private val starChar = '★'
    private val profileRe =
        Regex("""steamcommunity\.com/(?:profiles/(\d+)|id/([A-Za-z0-9_]+))""")
    private val nickRe =
        Regex("""class="playerNickname ellipsis"><a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }

    // ---------- 对外 ----------

    fun parse(html: String, tab: String, mySteamId: String, myName: String): Result {
        if (!html.contains("val_left")) return Result(emptyList(), 0, 0)

        val chunks = html.split(cellSplit).drop(1)
        if (chunks.isEmpty()) return Result(emptyList(), 0, 0)

        val matches = ArrayList<MatchDetail>(chunks.size)
        var bad = 0
        for (chunk in chunks) {
            val m = runCatching { parseOne(chunk, tab, mySteamId, myName) }.getOrNull()
            if (m != null) matches += m else bad++
        }
        return Result(matches, chunks.size, bad)
    }

    // ---------- 单场比赛 ----------

    private fun parseOne(
        chunk: String,
        tab: String,
        mySteamId: String,
        myName: String,
    ): MatchDetail? {
        val left = leftTable.find(chunk)?.groupValues?.get(1) ?: return null
        val right = rightTable.find(chunk)?.groupValues?.get(1) ?: return null

        // ---- 左栏：模式 / 地图 / 时间 / 时长 / demo ----
        val leftCells = plainTd.findAll(left).map { it.groupValues[1].trim() }.toList()
        val timeText = leftCells.firstOrNull { dateRe.containsMatchIn(it) } ?: return null
        val epoch = runCatching { dateFmt.parse(timeText.trim())!!.time / 1000 }.getOrNull() ?: return null

        val durationSec = clockSec(durationRe.find(left)) ?: 0
        val waitTimeSec = clockSec(waitTimeRe.find(left))
        val ranked = rankedRe.find(left)?.groupValues?.get(1)?.equals("Yes", ignoreCase = true)
        val replayUrl = replayUrlRe.find(chunk)?.value

        val demoId = demoRe.find(chunk)?.groupValues?.get(1)

        // ---- 比分（夹在两组球员之间） ----
        val score = scoreTd.find(right) ?: return null
        val roundsA = score.groupValues[1].toInt()
        val roundsB = score.groupValues[2].toInt()
        val totalRounds = roundsA + roundsB
        if (totalRounds <= 0) return null

        // ---- 右栏：10 人记分板，按比分行切成两组 ----
        val players = ArrayList<PlayerLine>(10)
        var team = Team.TEAM_A
        for (row in right.split("<tr>")) {
            when {
                row.contains("csgo_scoreboard_score") -> team = Team.TEAM_B
                row.contains("inner_name") -> parsePlayer(row, team, totalRounds)?.let { players += it }
            }
        }
        if (players.isEmpty()) return null

        // ---- 我在第几组（决定 myTeam 与胜负方向） ----
        val myIndex = players.indexOfFirst { isMe(it, mySteamId, myName) }
        val myTeam = when {
            myIndex >= 0 && players[myIndex].team == Team.TEAM_B -> Team.TEAM_B
            else -> Team.TEAM_A
        }
        // 自己那行若走的是 vanity 链接（/id/xxx），回填成数字 SteamID，后续 myLine() 才能命中
        val fixed = if (myIndex >= 0 && mySteamId.isNotBlank() &&
            players[myIndex].steamId != mySteamId && !mySteamId.startsWith("id/")
        ) {
            players.mapIndexed { i, p -> if (i == myIndex) p.copy(steamId = mySteamId) else p }
        } else players

        val (myRounds, enemyRounds) =
            if (myTeam == Team.TEAM_A) roundsA to roundsB else roundsB to roundsA

        // ---- 模式与地图 ----
        val modeZh = modeZh(tab)
        val mapCell = leftCells.firstOrNull {
            !dateRe.containsMatchIn(it) &&
                !it.startsWith("Ranked") && !it.startsWith("Wait Time") &&
                !it.startsWith("Match Duration")
        } ?: return null
        val mapEn = stripMode(mapCell, modeZh, modeEn(tab))

        return MatchDetail(
            id = demoId?.let { "gcpd-$it" } ?: "gcpd-$epoch-$mapEn".lowercase().replace(' ', '_'),
            mapName = displayMap(mapEn),
            mode = modeZh,
            startedAt = epoch,
            durationSec = durationSec,
            myTeam = myTeam,
            myScore = myRounds,
            enemyScore = enemyRounds,
            players = fixed,
            hasRoundEconomy = false,
            ranked = ranked,
            waitTimeSec = waitTimeSec,
            replayUrl = replayUrl,
        )
    }

    /**
     * 解析一行球员数据。
     *
     * 列序固定为 `Ping K A D ★ HSP Score`，但 **★ 与 HSP 列可能整个缺失**（无 MVP、
     * 无爆头时页面直接省略或写 `&nbsp;`），个别行甚至没有 Ping 列，所以不写死下标：
     * 先定位 `★` / `%`，K/A/D 三列必定紧贴其**前**，Ping 在 K 之前、Score 在最后。
     */
    private fun parsePlayer(row: String, team: Team, rounds: Int): PlayerLine? {
        val idm = profileRe.find(row) ?: return null
        val steamId = idm.groupValues[1].ifBlank { "id/" + idm.groupValues[2] }
        val name = nickRe.find(row)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }
            ?.takeIf { it.isNotEmpty() } ?: "未知"

        var cells = plainTd.findAll(row).map { decodeEntities(it.groupValues[1].trim()) }.toList()
        if (cells.size < 4) return null
        // Ping 列可能整格写成 &nbsp;（机器人/断线）：丢掉它，避免把 Ping 当成 K
        if (cells[0].isBlank()) cells = cells.drop(1)

        // K/A/D 三连的右边界（开区间）：Ping/K/A/D/Score 都是整数，★ 与 HSP(%) 不是，
        // 所以**第一个非整数格**就是 K/A/D 的收尾处。这样能同时兼容：
        //   · 标准 7 列          59 | 21 | 6 | 18 | ★3 | 42% | 53
        //   · ★ 列写成 &nbsp;    56 | 4  | 3 | 9  |    | 75% | 9
        //   · ★ 整列省略          32 | 8  | 2 | 8  | 12% | 17
        //   · 老样本被 GBK 误解码成 `鈽?` 也照样是“非整数” => 边界照样对
        //   · 一个非整数都没有（只剩整数列）=> 按标准布局取到 Score 为止
        val marker = (1 until cells.size).firstOrNull { cells[it].toIntOrNull() == null }
        val end = marker ?: ((cells.size - 1).takeIf { it >= 4 } ?: return null)
        if (end < 3) return null

        val kills = cells[end - 3].toIntOrNull() ?: return null
        val assists = cells[end - 2].toIntOrNull() ?: 0
        val deaths = cells[end - 1].toIntOrNull() ?: return null

        // HSP 是唯一带 % 的列；图标列（U+E43D 私有区字符）与缺失值（&nbsp;）一律跳过
        val hsp = hspCell(cells)?.filter { it.isDigit() }?.toIntOrNull()
        val ping = cells.getOrNull(end - 4)?.toIntOrNull()
        val mvps = cells.indexOfFirst { it.contains(starChar) }
            .takeIf { it in cells.indices }
            ?.let { cells[it].filter { c -> c.isDigit() }.toIntOrNull() } ?: 0
        val score = cells.lastOrNull()?.toIntOrNull()

        val headshotKills = if (hsp != null && kills > 0) {
            (kills * hsp / 100.0).roundToInt()
        } else 0

        return PlayerLine(
            steamId = steamId,
            name = name,
            team = team,
            roundsPlayed = rounds,
            kills = kills,
            deaths = deaths,
            assists = assists,
            headshotKills = headshotKills,
            damage = 0,               // 官匹页面不提供伤害
            kastRounds = null,        // 以下同理：缺席 => Rating 自动降级
            survivedRounds = null,    // 生存由 PlayerLine.survivedRoundsEff 按恒等式反推
            multiKillRounds = null,
            openingKills = null,
            openingDeaths = null,
            ping = ping,
            mvps = mvps,
            score = score,
        )
    }

    private fun hspCell(cells: List<String>): String? =
        cells.firstOrNull { it.contains('%') }

    /** `38:03` / `01:15` / `1:02:03` → 秒；匹配不到返回 null。 */
    private fun clockSec(m: kotlin.text.MatchResult?): Int? {
        val mm = m ?: return null
        val p = mm.groupValues.drop(1).filter { it.isNotEmpty() }.map { it.toInt() }
        return when (p.size) {
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            2 -> p[0] * 60 + p[1]
            else -> p.firstOrNull()
        }
    }

    // ---------- 小工具 ----------

    private fun isMe(p: PlayerLine, mySteamId: String, myName: String): Boolean =
        (mySteamId.isNotBlank() && p.steamId == mySteamId) ||
            (myName.isNotBlank() && p.name == myName)

    private fun modeEn(tab: String): String = when {
        tab.contains("premier") -> "Premier"
        tab.contains("competitive") -> "Competitive"
        tab.contains("wingman") -> "Wingman"
        tab.contains("scrimmage") -> "Scrimmage"
        tab.contains("rush") -> "Rush"
        tab.contains("casual") -> "Casual"
        else -> ""
    }

    private fun modeZh(tab: String): String = when {
        tab.contains("premier") -> "优先匹配"
        tab.contains("competitive") -> "竞技"
        tab.contains("wingman") -> "双人搭档"
        tab.contains("scrimmage") -> "非排位"
        tab.contains("rush") -> "Rush"
        tab.contains("casual") -> "休闲"
        else -> tab
    }

    /** 「Premier Ancient」→「Ancient」。 */
    private fun stripMode(cell: String, zh: String, en: String): String =
        cell.removePrefix(en).removePrefix(zh).trim().ifBlank { cell.trim() }

    private val MAP_ZH = mapOf(
        "Ancient" to "远古遗迹",
        "Anubis" to "阿努比斯",
        "Cache" to "死城之谜",
        "Dust II" to "炙热沙城2",
        "Inferno" to "炼狱小镇",
        "Mirage" to "荒漠迷宫",
        "Nuke" to "核子危机",
        "Overpass" to "死亡游乐园",
        "Train" to "列车停放站",
        "Vertigo" to "殒命大厦",
        "Office" to "办公室",
        "Italy" to "意大利",
        "Militia" to "民兵",
    )

    /** 与示例数据保持同一种展示风格：「远古遗迹 (Ancient)」；不认识的图直接用英文名。 */
    private fun displayMap(en: String): String {
        val key = MAP_ZH.keys.firstOrNull { it.equals(en, ignoreCase = true) }
            ?: return en.ifBlank { "未知地图" }
        return "${MAP_ZH.getValue(key)} ($key)"
    }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()
}
