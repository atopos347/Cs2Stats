package com.cs2stats.app

import com.cs2stats.app.data.collect.GcpdParser
import com.cs2stats.app.data.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 **真实登录态抓到的样本**（`gcpd_p1.html`，2026-09-28 来自本人账号）
 * 校准 gcpd 解析器，防止结构变化时悄悄算错。
 */
class GcpdParserTest {

    private val mySteamId = "76561199517803315" // Atopos
    private val myName = "Atopos"

    private fun sample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("gcpd_p1.html"))
            .bufferedReader().readText()

    @Test
    fun `解析出样本里的 8 场比赛`() {
        val r = GcpdParser.parse(sample(), "matchhistorypremier", mySteamId, myName)
        assertEquals("未识别的比赛块：${r.unparseable}", 0, r.unparseable)
        assertEquals(8, r.matches.size)
        // 按开赛时间倒序
        assertTrue(r.matches.zipWithNext().all { (a, b) -> a.startedAt >= b.startedAt })
    }

    @Test
    fun `首场：地图时间比分与我的位置都正确`() {
        val m = GcpdParser.parse(sample(), "matchhistorypremier", mySteamId, myName).matches.first()

        assertEquals("远古遗迹 (Ancient)", m.mapName)
        assertEquals("优先匹配", m.mode)
        assertEquals(38 * 60 + 3, m.durationSec) // Match Duration: 38:03
        assertEquals(10, m.players.size)

        // 2026-09-28 08:52:25 GMT
        val expected = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.parse("2026-09-28 08:52:25")!!.time / 1000
        assertEquals(expected, m.startedAt)

        // 比分 13 : 11，我在第 1 组（TEAM_B）
        assertEquals(Team.TEAM_B, m.myTeam)
        assertEquals(11, m.myScore)
        assertEquals(13, m.enemyScore)
        assertEquals(24, m.players.first().roundsPlayed)

        // 左栏：Ranked: Yes / Wait Time: 01:15 / demo 直链
        assertEquals(true, m.ranked)
        assertEquals(75, m.waitTimeSec)
        assertEquals(
            "http://replay403.valve.net/730/003845258388027998412_0649725551.dem.bz2",
            m.replayUrl,
        )
    }

    @Test
    fun `我这一行的 KDA 与爆头率正确`() {
        val m = GcpdParser.parse(sample(), "matchhistorypremier", mySteamId, myName).matches.first()
        val me = m.myLine(mySteamId)
        assertNotNull("myLine 没能命中我的行", me)

        assertEquals("Atopos", me!!.name)
        assertEquals(21, me.kills)
        assertEquals(18, me.deaths)
        assertEquals(6, me.assists)
        // HSP 42% × 21 杀 ≈ 8.8 → 9
        assertEquals(9, me.headshotKills)
        assertTrue(Math.abs(me.headshotRate - 9.0 / 21) < 1e-6)
        // 记分板另外三列：59 = Ping，53 = Score（★ 列在该样本被 GBK 误解码，仅断言不为负）
        assertEquals(59, me.ping)
        assertEquals(53, me.score)
        assertTrue((me.mvps ?: -1) >= 0)
        // 官匹页面不提供这些字段 => 必须保持缺失，供 Rating 降级
        assertEquals(0, me.damage)
        assertEquals(null, me.kastRounds)
        assertEquals(null, me.survivedRounds)
        assertEquals(null, me.multiKillRounds)
        assertEquals(null, me.openingKills)
        // 但存活可以按恒等式反推：24 回合 − 18 死 = 6 存活（标注为 ≈ 反推）
        assertEquals(6, me.survivedRoundsEff)
        assertEquals(0.25, me.survivalRate!!, 1e-9)
        assertTrue(me.survivalDerived)
    }

    @Test
    fun `胜负方向由我所在的组决定`() {
        val r = GcpdParser.parse(sample(), "matchhistorypremier", mySteamId, myName)

        // 样本 8 场比分依次为 13:11 / 1:11 / 13:5 / 9:13 / 5:13 / 13:10 / 13:6 / 7:13，
        // 我分别落在第 1/0/1/0/0/1/0/1 组，故我的回合数 => 胜负如右。
        val results = r.matches.map {
            if (it.myScore > it.enemyScore) "W" else if (it.myScore < it.enemyScore) "L" else "T"
        }
        assertEquals(listOf("L", "L", "L", "L", "L", "L", "W", "W"), results)

        // MR12 不变量：优先匹配先拿 13 回合者胜，所以每一场“胜利”都必须 ≥13 回合。
        // 这条能反过来验证「比分 = 第0组 : 第1组」的读法没被颠倒。
        assertTrue(r.matches.filter { it.result.name == "WIN" }.all { it.myScore >= 13 })
    }

    @Test
    fun `每场都有稳定主键且互不相同`() {
        val r = GcpdParser.parse(sample(), "matchhistorypremier", mySteamId, myName)
        assertEquals(r.matches.size, r.matches.map { it.id }.distinct().size)
        assertTrue(r.matches.all { it.id.startsWith("gcpd-") })
    }
}
