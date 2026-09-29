package com.cs2stats.app.data.remote

import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.PlayerLine
import com.cs2stats.app.data.model.SteamProfile
import com.cs2stats.app.data.model.Team
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 自建后端的 HTTP 客户端。
 *
 * 约定的接口（后端按此实现即可对接）：
 *
 * ```
 * GET {base}/api/v1/profile                 -> { steamId, personaName, avatarUrl, profileUrl }
 * GET {base}/api/v1/matches?limit=100       -> { "matches": [ <match> ] }
 * GET {base}/api/v1/matches/{id}            -> <match>
 *
 * <match> = {
 *   "id": "...", "map": "mirage", "mode": "competitive",
 *   "startedAt": 1758000000, "durationSec": 2400,
 *   "myTeam": "TEAM_A", "myScore": 13, "enemyScore": 9,
 *   "hasRoundEconomy": false,
 *   "players": [ { "steamId": "...", "name": "...", "team": "TEAM_A",
 *                  "rounds": 22, "kills": 18, "deaths": 14, "assists": 4,
 *                  "hsKills": 9, "damage": 1720,
 *                  "kastRounds": 16, "survivedRounds": 7,
 *                  "multiKillRounds": 4, "openingKills": 3, "openingDeaths": 2 } ]
 * }
 * 缺失的数值字段视为「数据源没有提供」，Rating 会自动降级。
 * ```
 *
 * 上传接口（手机检测到新比赛后写入数据库）预留为：
 * ```
 * POST {base}/api/v1/matches      body: <match>
 * ```
 *
 * > 曾经还有 `inventory`（库存）相关的读写接口，2026-09-29 按需求整体移除库存功能，
 * > 代码备份在 `backups/20260929-remove-inventory/`。
 */
class ApiClient(baseUrl: String, private val token: String) {

    private val base = baseUrl.trim().trimEnd('/')

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun profile(): SteamProfile = get("api/v1/profile").let { json ->
        SteamProfile(
            steamId = json.optString("steamId"),
            personaName = json.optString("personaName"),
            avatarUrl = json.optString("avatarUrl"),
            profileUrl = json.optString("profileUrl"),
        )
    }

    suspend fun matches(limit: Int = 100): List<MatchDetail> =
        parseMatches(get("api/v1/matches?limit=$limit").toString())

    /** 上传一场比赛（后端未实现时会抛错，调用方已静默处理）。 */
    suspend fun uploadMatch(match: MatchDetail) {
        post("api/v1/matches", matchToJson(match).toString())
    }

    // ---------- 底层 ----------

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/$path")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}: ${body.take(200)}")
            JSONObject(body)
        }
    }

    private suspend fun post(path: String, body: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/$path")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}: ${text.take(200)}")
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    companion object {
        /** 解析 `{ "matches": [...] }`（网盘 matches.json 或 GET /matches 响应）。 */
        fun parseMatches(root: JSONObject): List<MatchDetail> {
            val arr = root.optJSONArray("matches") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseMatch(it) } }
        }

        fun parseMatches(json: String): List<MatchDetail> =
            runCatching { parseMatches(JSONObject(json)) }.getOrElse { emptyList() }

        /** 解析 profile.json。 */
        fun parseProfile(json: String): SteamProfile? = runCatching {
            val o = JSONObject(json)
            SteamProfile(
                steamId = o.optString("steamId"),
                personaName = o.optString("personaName"),
                avatarUrl = o.optString("avatarUrl"),
                profileUrl = o.optString("profileUrl"),
            )
        }.getOrNull()?.takeIf { it.steamId.isNotBlank() && it.steamId != "null" }

        fun matchesToJson(matches: List<MatchDetail>): String = JSONObject()
            .put("matches", JSONArray().also { arr -> matches.forEach { arr.put(matchToJson(it)) } })
            .toString()

        fun profileToJson(p: SteamProfile): String = JSONObject()
            .put("steamId", p.steamId)
            .put("personaName", p.personaName)
            .put("avatarUrl", p.avatarUrl)
            .put("profileUrl", p.profileUrl)
            .toString()

        fun parseMatch(o: JSONObject): MatchDetail? {
            val id = o.optString("id")
            if (id.isNullOrBlank() || id == "null") return null
            val playersArr = o.optJSONArray("players") ?: return null
            val players = (0 until playersArr.length()).mapNotNull { i ->
                playersArr.optJSONObject(i)?.let { parsePlayer(it) }
            }
            return MatchDetail(
                id = id,
                mapName = o.optString("map", "未知"),
                mode = o.optString("mode", "competitive"),
                startedAt = o.optLong("startedAt", 0L),
                durationSec = o.optInt("durationSec", 0),
                myTeam = runCatching { Team.valueOf(o.optString("myTeam", "TEAM_A")) }.getOrDefault(Team.TEAM_A),
                myScore = o.optInt("myScore", 0),
                enemyScore = o.optInt("enemyScore", 0),
                players = players,
                hasRoundEconomy = o.optBoolean("hasRoundEconomy", false),
                ranked = if (o.has("ranked") && !o.isNull("ranked")) o.optBoolean("ranked") else null,
                waitTimeSec = o.optIntOrNull("waitTimeSec"),
                replayUrl = o.optString("replayUrl").takeIf { it.isNotBlank() && it != "null" },
                demoParsedAt = o.optLongOrNull("demoParsedAt"),
            )
        }

        private fun parsePlayer(o: JSONObject): PlayerLine? {
            val name = o.optString("name")
            if (name.isNullOrBlank() || name == "null") return null
            return PlayerLine(
                steamId = o.optString("steamId", ""),
                name = name,
                team = runCatching { Team.valueOf(o.optString("team", "TEAM_A")) }.getOrDefault(Team.TEAM_A),
                roundsPlayed = o.optInt("rounds", 0),
                kills = o.optInt("kills", 0),
                deaths = o.optInt("deaths", 0),
                assists = o.optInt("assists", 0),
                headshotKills = o.optInt("hsKills", 0),
                damage = o.optInt("damage", 0),
                kastRounds = o.optIntOrNull("kastRounds"),
                survivedRounds = o.optIntOrNull("survivedRounds"),
                multiKillRounds = o.optIntOrNull("multiKillRounds"),
                openingKills = o.optIntOrNull("openingKills"),
                openingDeaths = o.optIntOrNull("openingDeaths"),
                ping = o.optIntOrNull("ping"),
                mvps = o.optIntOrNull("mvps"),
                score = o.optIntOrNull("score"),
            )
        }

        fun matchToJson(m: MatchDetail): JSONObject = JSONObject().apply {
            put("id", m.id)
            put("map", m.mapName)
            put("mode", m.mode)
            put("startedAt", m.startedAt)
            put("durationSec", m.durationSec)
            put("myTeam", m.myTeam.name)
            put("myScore", m.myScore)
            put("enemyScore", m.enemyScore)
            put("hasRoundEconomy", m.hasRoundEconomy)
            m.ranked?.let { put("ranked", it) }
            m.waitTimeSec?.let { put("waitTimeSec", it) }
            m.replayUrl?.let { put("replayUrl", it) }
            m.demoParsedAt?.let { put("demoParsedAt", it) }
            put(
                "players",
                org.json.JSONArray().also { arr ->
                    m.players.forEach { p ->
                        arr.put(JSONObject().apply {
                            put("steamId", p.steamId)
                            put("name", p.name)
                            put("team", p.team.name)
                            put("rounds", p.roundsPlayed)
                            put("kills", p.kills)
                            put("deaths", p.deaths)
                            put("assists", p.assists)
                            put("hsKills", p.headshotKills)
                            put("damage", p.damage)
                            p.kastRounds?.let { put("kastRounds", it) }
                            p.survivedRounds?.let { put("survivedRounds", it) }
                            p.multiKillRounds?.let { put("multiKillRounds", it) }
                            p.openingKills?.let { put("openingKills", it) }
                            p.openingDeaths?.let { put("openingDeaths", it) }
                            p.ping?.let { put("ping", it) }
                            p.mvps?.let { put("mvps", it) }
                            p.score?.let { put("score", it) }
                        })
                    }
                }
            )
        }

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key) else null
    }
}

class ApiException(val code: Int, message: String) : Exception(message)
