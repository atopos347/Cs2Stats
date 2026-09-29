package com.cs2stats.app.data.mock

import com.cs2stats.app.data.model.MatchDetail
import com.cs2stats.app.data.model.PlayerLine
import com.cs2stats.app.data.model.SteamProfile
import com.cs2stats.app.data.model.Team
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 内置示例数据：在未配置自建后端时让界面与算法先跑起来。
 *
 * 数据为**确定性生成**（固定随机种子），方便回归对比：
 * 字段结构与后端 JSON 完全一致，接上真实服务后直接替换即可。
 */
object MockData {

    const val MY_STEAM_ID = "76561198000000000"
    private const val MY_NAME = "你"

    private val MAPS = listOf(
        "Mirage" to "荒漠迷城", "Inferno" to "炼狱小镇", "Dust II" to "炙热沙城 II",
        "Nuke" to "死城之谜", "Ancient" to "远古遗迹", "Anubis" to "阿努比斯",
        "Vertigo" to "殒命大厦", "Overpass" to "死亡游乐园",
    )

    private val TEAMMATES = listOf("残局大师", "道具鬼才", "刚枪王K", "老六本六", "补枪位")
    private val OPPONENTS = listOf("对面大狙", "Rush B", "eco战神", "压A的人", "拆包侠")

    fun profile() = SteamProfile(
        steamId = MY_STEAM_ID,
        personaName = MY_NAME,
        avatarUrl = "",
        profileUrl = "https://steamcommunity.com/profiles/$MY_STEAM_ID",
    )

    /** 生成 [count] 场比赛（最近的在前）。 */
    fun matches(count: Int = 16): List<MatchDetail> {
        val rnd = Random(20260928L)
        val now = System.currentTimeMillis() / 1000
        return (0 until count).map { i ->
            val (mapEn, mapZh) = MAPS[i % MAPS.size]
            val win = rnd.nextInt(10) < 7
            val myScore: Int
            val enemyScore: Int
            if (win) {
                myScore = 13
                enemyScore = 4 + rnd.nextInt(0, 9)          // 13:4 ~ 13:12
            } else {
                enemyScore = 13
                myScore = 3 + rnd.nextInt(0, 10)
            }
            val rounds = myScore + enemyScore
            val myTeam = if (rnd.nextBoolean()) Team.TEAM_A else Team.TEAM_B
            val enemyTeam = if (myTeam == Team.TEAM_A) Team.TEAM_B else Team.TEAM_A

            // 我本场状态：好局 / 平常局 / 坏局
            val mySkill = when (rnd.nextInt(10)) {
                in 0..2 -> 0.85 + rnd.nextDouble() * 0.15
                in 3..6 -> 0.50 + rnd.nextDouble() * 0.25
                else -> 0.20 + rnd.nextDouble() * 0.25
            }

            val players = buildList {
                add(line(MY_STEAM_ID, MY_NAME, myTeam, rounds, rnd, mySkill))
                TEAMMATES.forEachIndexed { idx, n ->
                    add(line("765611980001000$idx", n, myTeam, rounds, rnd, 0.30 + rnd.nextDouble() * 0.55))
                }
                OPPONENTS.forEachIndexed { idx, n ->
                    add(line("765611980002000$idx", n, enemyTeam, rounds, rnd, 0.30 + rnd.nextDouble() * 0.55))
                }
            }

            MatchDetail(
                id = "mock-$i",
                mapName = "$mapZh ($mapEn)",
                mode = "竞技",
                startedAt = now - i * 9_000L - rnd.nextInt(0, 3_600).toLong(),
                durationSec = rounds * 105 + rnd.nextInt(120, 480),
                myTeam = myTeam,
                myScore = myScore,
                enemyScore = enemyScore,
                players = players,
                hasRoundEconomy = false, // 官匹没有回合级经济 → Swing 走近似
            )
        }
    }

    /** 造一名玩家的一行数据，字段口径与后端 JSON 一致。 */
    private fun line(
        steamId: String, name: String, team: Team,
        rounds: Int, rnd: Random, skill: Double,
    ): PlayerLine {
        val kpr = (0.45 + skill * 0.40 + (rnd.nextDouble() - 0.5) * 0.16).coerceIn(0.22, 1.15)
        val kills = (kpr * rounds).roundToInt().coerceIn(0, rounds * 2)

        val dpr = (0.66 - skill * 0.14 + (rnd.nextDouble() - 0.5) * 0.22).coerceIn(0.30, 1.10)
        val deaths = (dpr * rounds).roundToInt().coerceIn(0, rounds)

        val hsRate = (0.34 + skill * 0.20 + rnd.nextDouble() * 0.16).coerceIn(0.15, 0.85)
        val hsKills = (kills * hsRate).roundToInt().coerceIn(0, kills)

        val adr = (55 + skill * 38 + rnd.nextDouble() * 22).coerceIn(42.0, 125.0)
        val damage = (adr * rounds).roundToInt()

        val kastRate = (0.56 + skill * 0.20 + rnd.nextDouble() * 0.13).coerceIn(0.45, 0.88)
        val kastRounds = (kastRate * rounds).roundToInt().coerceIn(0, rounds)

        val survRate = (0.17 + skill * 0.14 + rnd.nextDouble() * 0.11).coerceIn(0.10, 0.45)
        val survivedRounds = (survRate * rounds).roundToInt().coerceIn(0, rounds)

        val mkRate = (0.07 + skill * 0.15 + rnd.nextDouble() * 0.10).coerceIn(0.03, 0.32)
        val multiKillRounds = (mkRate * rounds).roundToInt().coerceIn(0, rounds)

        val okRate = (0.06 + skill * 0.12 + rnd.nextDouble() * 0.08).coerceIn(0.02, 0.26)
        val openingKills = (okRate * rounds).roundToInt().coerceIn(0, rounds)
        val odRate = (0.07 + (1 - skill) * 0.11 + rnd.nextDouble() * 0.08).coerceIn(0.02, 0.28)
        val openingDeaths = (odRate * rounds).roundToInt().coerceIn(0, rounds)

        return PlayerLine(
            steamId = steamId,
            name = name,
            team = team,
            roundsPlayed = rounds,
            kills = kills,
            deaths = deaths,
            assists = (rounds * (0.10 + rnd.nextDouble() * 0.25)).roundToInt(),
            headshotKills = hsKills,
            damage = damage,
            kastRounds = kastRounds,
            survivedRounds = survivedRounds,
            multiKillRounds = multiKillRounds,
            openingKills = openingKills,
            openingDeaths = openingDeaths,
        )
    }
}
