package com.cs2stats.app.data.model

/** Steam 账号资料（登录后回填）。 */
data class SteamProfile(
    val steamId: String,
    val personaName: String,
    val avatarUrl: String = "",
    val profileUrl: String = "",
)

/**
 * 单场比赛中**一名玩家**的原始战绩行。
 *
 * 字段带 `?` 表示数据源不一定提供（Steam 官匹战绩页缺少 KAST / 伤害 / 开局杀等字段，
 * 需要自建后端从 demo 或其它数据源补齐）。缺失的字段会让对应的 Rating 子项缺席，
 * 由 [com.cs2stats.app.data.rating.Rating3] 自动按权重重分配。
 */
data class PlayerLine(
    val steamId: String,
    val name: String,
    val team: Team,
    val roundsPlayed: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int = 0,
    val headshotKills: Int = 0,
    val damage: Int = 0,
    val kastRounds: Int? = null,
    val survivedRounds: Int? = null,
    val multiKillRounds: Int? = null,
    val openingKills: Int? = null,
    val openingDeaths: Int? = null,
    // ---- Steam 官匹记分板**直接给出**的列（数据源可选，旧档案可能没有）----
    val ping: Int? = null,
    val mvps: Int? = null,      // ★ 全场 MVP 星数
    val score: Int? = null,     // 记分板最右列 Score
) {
    val kpr: Double get() = if (roundsPlayed > 0) kills.toDouble() / roundsPlayed else 0.0
    val adr: Double get() = if (roundsPlayed > 0) damage.toDouble() / roundsPlayed else 0.0
    val headshotRate: Double get() = if (kills > 0) headshotKills.toDouble() / kills else 0.0
    val kd: Double get() = if (deaths > 0) kills.toDouble() / deaths else kills.toDouble()

    /**
     * **存活回合**：优先用数据源给的值；没有时按恒等式反推 ——
     * CS 规则下**每回合最多阵亡一次**，故 `存活回合 = 回合数 − 阵亡数`，
     * 不是估算而是恒等关系（UI 与 Rating 用 `≈` 标注为「反推」）。
     *
     * 已知偏差：回合数按整场计算，若该玩家**中途退赛**，未参与的回合会被算作存活（偏高）。
     */
    val survivedRoundsEff: Int?
        get() = survivedRounds
            ?: if (roundsPlayed > 0 && deaths in 0..roundsPlayed) roundsPlayed - deaths else null

    val survivalRate: Double?
        get() = survivedRoundsEff?.let { if (roundsPlayed > 0) it.toDouble() / roundsPlayed else null }

    /** 生存值是否为反推（而非数据源直接提供）。 */
    val survivalDerived: Boolean get() = survivedRounds == null && survivedRoundsEff != null
}

enum class Team { CT, T, TEAM_A, TEAM_B }

enum class MatchResult { WIN, LOSS, TIE }

/** 一场比赛。 */
data class MatchDetail(
    val id: String,
    val mapName: String,
    val mode: String,
    val startedAt: Long,          // epoch 秒
    val durationSec: Int = 0,
    val myTeam: Team,
    val myScore: Int,
    val enemyScore: Int,
    val players: List<PlayerLine>,
    val hasRoundEconomy: Boolean = false, // 后端是否提供了回合级经济数据（决定 Round Swing 能否精确计算）
    /** 排位状态：页面有 `Ranked: Yes/No` 行才给值（Rush 等模式没有该行）。 */
    val ranked: Boolean? = null,
    /** 匹配等待时长（秒）。 */
    val waitTimeSec: Int? = null,
    /** Valve demo 回放直链（约 30 天后过期）。 */
    val replayUrl: String? = null,
    /**
     * 本机 demo 解析完成时间（epoch 毫秒）。
     *
     * 非 null 表示 [damage] / [PlayerLine.kastRounds] / [PlayerLine.multiKillRounds] /
     * [PlayerLine.openingKills] / [PlayerLine.survivedRounds] 这些**战绩页拿不到的字段**
     * 已由本地 demo 解析补齐；K/A/D/HS/★/Score 仍以战绩页为准（两者不一致时以战绩页为准）。
     */
    val demoParsedAt: Long? = null,
) {
    val result: MatchResult
        get() = when {
            myScore > enemyScore -> MatchResult.WIN
            myScore < enemyScore -> MatchResult.LOSS
            else -> MatchResult.TIE
        }

    /** 我这一行（找不到时返回 null）。 */
    fun myLine(mySteamId: String?): PlayerLine? =
        players.firstOrNull { it.steamId == mySteamId } ?: players.firstOrNull { it.team == myTeam }
}

/** 总览聚合。 */
data class OverallStats(
    val matchCount: Int = 0,
    val winCount: Int = 0,
    val avgRating: Double = 0.0,
    val avgHeadshotRate: Double = 0.0,
    val kdRatio: Double = 0.0,
    val kastRate: Double = 0.0,
    val avgAdr: Double = 0.0,
    val avgKpr: Double = 0.0,
) {
    val winRate: Double get() = if (matchCount > 0) winCount.toDouble() / matchCount else 0.0
}
