package com.cs2stats.app.data.demo

import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.PlayerLine
import com.cs2stats.app.data.model.Team
import org.json.JSONObject
import kotlin.math.abs

/** 合并被拒绝时抛出；[message] 直接给用户看（不含技术细节）。 */
class DemoMergeException(message: String) : Exception(message)

/**
 * 把 demo 解析结果**合并**进一场已有的战绩，补出 Steam 战绩页拿不到的字段。
 *
 * 合并铁律（对应「不得编造数据」「解析不得与抓取数据冲突」）：
 *
 * 1. **只补空缺，不覆盖**：`damage / kastRounds / survivedRounds / multiKillRounds /
 *    openingKills / openingDeaths` 里战绩页已有的值一律保留，只有为 0 / null 才用 demo 值填；
 * 2. **K/A/D/爆头/★/Score 永远以战绩页为准**（demo 侧只拿来**校验**，不写回）——
 *    实测 demo 与页面的助攻偶有 ±2 的口径差（Valve 的助攻判定不同源），
 *    小差异不该把页面数据改掉；
 * 3. **写回前先对账**：逐人比对 K/D，若与战绩页一致的人数不足 60%，判定这份 demo
 *    对不上这场比赛（比如拿错了回放），整份丢弃并报错，绝不写入可疑数据；
 * 4. demo 里有、战绩页没有的玩家（中途替换上场）按 demo 追加进来，并标注数据来自 demo。
 */
object DemoStatsMerger {

    /** 合并结果：更新后的比赛 + 一句给用户看的说明。 */
    data class Outcome(val match: MatchDetail, val note: String)

    /** demo 里的一名玩家（只取我们需要的列）。 */
    private data class Parsed(
        val steamId: String,
        val name: String,
        val team: String,
        val rounds: Int,
        val kills: Int,
        val deaths: Int,
        val assists: Int,
        val headshotKills: Int,
        val damage: Int,
        val kastRounds: Int,
        val survivedRounds: Int,
        val multiKillRounds: Int,
        val openingKills: Int,
        val openingDeaths: Int,
        val mvps: Int,
    )

    /**
     * @param json [DemoNative.parseJson] 的返回值（UTF-8 解码后的字符串）
     * @param now  合并时间戳（测试可注入）
     * @throws DemoMergeException 数据不可信或无法合并时
     */
    @JvmStatic
    @Throws(DemoMergeException::class)
    fun merge(match: MatchDetail, json: String, now: Long = System.currentTimeMillis()): Outcome {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw DemoMergeException("解析结果不是合法 JSON") }

        root.optString("error")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { throw DemoMergeException(it) }

        val parser = root.optString("parser").takeIf { it.isNotBlank() && it != "null" } ?: "未知解析器"
        val mapName = root.optString("mapName").takeIf { it.isNotBlank() && it != "null" } ?: match.mapName
        val roundsArr = root.optJSONArray("rounds")
            ?: throw DemoMergeException("解析结果缺少回合数据，未写入")
        if (roundsArr.length() < 2) {
            throw DemoMergeException("只统计到 ${roundsArr.length()} 个回合，数据不完整，未写入")
        }
        val playersArr = root.optJSONArray("players")
            ?: throw DemoMergeException("解析结果缺少玩家数据，未写入")
        if (playersArr.length() == 0) throw DemoMergeException("demo 里没有可识别的真人玩家")

        val parsed = (0 until playersArr.length()).mapNotNull { i ->
            playersArr.optJSONObject(i)?.let { parsePlayer(it) }
        }
        if (parsed.isEmpty()) throw DemoMergeException("demo 里没有可识别的真人玩家")

        val bySteam = parsed.filter { it.steamId.isNotBlank() }.associateBy { it.steamId }
        val byName = parsed.associateBy { it.name.lowercase() }

        // ---- 对账：demo 的 K/D 必须与战绩页大体一致 ----
        val matched = match.players.mapNotNull { line ->
            find(line, bySteam, byName)?.let { line to it }
        }
        if (matched.isEmpty()) throw DemoMergeException("解析结果里找不到这场比赛的任何玩家，未写入")
        val consistent = matched.count { (line, p) ->
            abs(line.kills - p.kills) <= 2 && abs(line.deaths - p.deaths) <= 2
        }
        val need = (matched.size * 0.6).toInt().coerceAtLeast(1)
        if (consistent < need) {
            throw DemoMergeException(
                "解析结果与战绩页对不上（${consistent}/${matched.size} 人一致），判定为错误的回放，未写入",
            )
        }

        // ---- 只补空缺：战绩页已有的值一律不动 ----
        val updated = match.players.map { line ->
            val p = find(line, bySteam, byName) ?: return@map line
            line.copy(
                damage = if (line.damage > 0) line.damage else p.damage,
                kastRounds = line.kastRounds ?: p.kastRounds,
                survivedRounds = line.survivedRounds ?: p.survivedRounds,
                multiKillRounds = line.multiKillRounds ?: p.multiKillRounds,
                openingKills = line.openingKills ?: p.openingKills,
                openingDeaths = line.openingDeaths ?: p.openingDeaths,
            )
        }

        // ---- demo 有、战绩页没有的玩家：按 demo 追加 ----
        val known = match.players
        val extra = parsed.filter { p ->
            known.none { it.steamId.isNotBlank() && it.steamId == p.steamId } &&
                known.none { it.name.equals(p.name, ignoreCase = true) }
        }
        val appended = extra.map { p ->
            PlayerLine(
                steamId = p.steamId,
                name = p.name,
                team = when (p.team) {
                    "CT" -> Team.CT
                    "T" -> Team.T
                    else -> if (match.myTeam == Team.CT) Team.CT else Team.T
                },
                roundsPlayed = p.rounds,
                kills = p.kills,
                deaths = p.deaths,
                assists = p.assists,
                headshotKills = p.headshotKills,
                damage = p.damage,
                kastRounds = p.kastRounds,
                survivedRounds = p.survivedRounds,
                multiKillRounds = p.multiKillRounds,
                openingKills = p.openingKills,
                openingDeaths = p.openingDeaths,
                mvps = p.mvps.takeIf { it > 0 },
            )
        }

        val note = buildString {
            append("已由本机 demo 解析补齐 ")
            append("${updated.size + appended.size} 人")
            append("：伤害/ADR、KAST、多杀、开局杀、存活")
            if (appended.isNotEmpty()) append("（追加 ${appended.size} 名替换上场玩家）")
            append("；K/A/D/爆头/★ 仍以战绩页为准")
            append(" · $mapName · ${roundsArr.length()} 回合 · $parser")
        }

        return Outcome(
            match = match.copy(
                players = updated + appended,
                demoParsedAt = now,
            ),
            note = note,
        )
    }

    /**
     * **抓取保护**：把已有的 demo 解析成果搬到新抓的页面记录上。
     *
     * 为什么必须有：「从 Steam 抓取」拿到的是**原始页面记录**（`damage=0`、
     * KAST/多杀/开局杀为 `null`、`demoParsedAt=null`），而抓取合并是
     * `distinctBy { id }` 让新抓的赢 —— 不搬运的话，**每次抓取都会把一场 142 MB
     * 换来的解析成果清零**，用户得重下重解析一遍。
     *
     * 口径与 [merge] 完全一致：
     * - **页面列一律取新抓的**（K/A/D、爆头、★MVP、Score、Ping、时长、比分…页面是权威来源）；
     * - 只把页面**拿不到**的 6 列（伤害/KAST/存活/多杀/开局杀·开局死）和 `demoParsedAt`
     *   从旧记录搬过来，且只在新记录为空时搬；
     * - 旧记录里由 [merge] 追加的「替换上场玩家」，若新抓的页面没有这一行，也一并保住
     *   （否则重新抓一次就把 demo 独有的那行丢了）。
     *
     * @param page     新抓取的页面记录
     * @param existing 数据空间里已有的同 id 记录；`null` 表示没见过，原样返回 [page]
     */
    @JvmStatic
    fun carryDemoFields(page: MatchDetail, existing: MatchDetail?): MatchDetail {
        if (existing == null || existing.id != page.id) return page

        val bySteam = existing.players.filter { it.steamId.isNotBlank() }.associateBy { it.steamId }
        val byName = existing.players.associateBy { it.name.lowercase() }

        val players = page.players.map { line ->
            val old = (if (line.steamId.isNotBlank()) bySteam[line.steamId] else null)
                ?: byName[line.name.lowercase()]
                ?: return@map line
            line.copy(
                damage = if (line.damage > 0) line.damage else old.damage,
                kastRounds = line.kastRounds ?: old.kastRounds,
                survivedRounds = line.survivedRounds ?: old.survivedRounds,
                multiKillRounds = line.multiKillRounds ?: old.multiKillRounds,
                openingKills = line.openingKills ?: old.openingKills,
                openingDeaths = line.openingDeaths ?: old.openingDeaths,
            )
        }

        // demo 追加的替换上场玩家：新页面没有这行才补，有就以页面为准
        val appended = if (existing.demoParsedAt == null) emptyList() else existing.players.filter { old ->
            page.players.none { it.steamId.isNotBlank() && it.steamId == old.steamId } &&
                page.players.none { it.name.equals(old.name, ignoreCase = true) }
        }

        return page.copy(
            players = players + appended,
            demoParsedAt = page.demoParsedAt ?: existing.demoParsedAt,
        )
    }

    private fun find(line: PlayerLine, bySteam: Map<String, Parsed>, byName: Map<String, Parsed>): Parsed? =
        if (line.steamId.isNotBlank()) bySteam[line.steamId] ?: byName[line.name.lowercase()]
        else byName[line.name.lowercase()]

    private fun parsePlayer(o: JSONObject): Parsed? {
        val name = o.optString("name")
        if (name.isBlank() || name == "null") return null
        return Parsed(
            steamId = o.optString("steamId").takeIf { it.isNotBlank() && it != "null" } ?: "",
            name = name,
            team = o.optString("team"),
            rounds = o.optInt("rounds", 0),
            kills = o.optInt("kills", 0),
            deaths = o.optInt("deaths", 0),
            assists = o.optInt("assists", 0),
            headshotKills = o.optInt("hsKills", 0),
            damage = o.optInt("damage", 0),
            kastRounds = o.optInt("kastRounds", 0),
            survivedRounds = o.optInt("survivedRounds", 0),
            multiKillRounds = o.optInt("multiKillRounds", 0),
            openingKills = o.optInt("openingKills", 0),
            openingDeaths = o.optInt("openingDeaths", 0),
            mvps = o.optInt("mvps", 0),
        )
    }
}
